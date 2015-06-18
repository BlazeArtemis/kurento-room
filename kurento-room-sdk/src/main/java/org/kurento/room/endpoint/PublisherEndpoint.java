/*
 * (C) Copyright 2013 Kurento (http://kurento.org/)
 * 
 * All rights reserved. This program and the accompanying materials are made
 * available under the terms of the GNU Lesser General Public License (LGPL)
 * version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl-2.1.html
 * 
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package org.kurento.room.endpoint;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

import org.kurento.client.ListenerSubscription;
import org.kurento.client.MediaElement;
import org.kurento.client.MediaPipeline;
import org.kurento.client.PassThrough;
import org.kurento.client.WebRtcEndpoint;
import org.kurento.room.exception.RoomException;
import org.kurento.room.exception.RoomException.Code;
import org.kurento.room.internal.Participant;

/**
 * Publisher aspect of the {@link TrickleIceEndpoint}.
 * 
 * @author <a href="mailto:rvlad@naevatec.com">Radu Tom Vlad</a>
 */
public class PublisherEndpoint extends IceWebRtcEndpoint implements
		MediaShapingEndpoint {

	private PassThrough passThru = null;
	private ListenerSubscription passThruSubscription = null;

	private Map<String, MediaElement> elements =
			new HashMap<String, MediaElement>();
	private LinkedList<String> elementIds = new LinkedList<String>();
	private boolean connected = false;

	private Map<String, ListenerSubscription> elementsErrorSubscriptions =
			new HashMap<String, ListenerSubscription>();

	public PublisherEndpoint(Participant owner, String endpointName,
			MediaPipeline pipeline) {
		super(owner, endpointName, pipeline);
	}

	@Override
	protected void internalEndpointInitialization() {
		super.internalEndpointInitialization();
		passThru = new PassThrough.Builder(getPipeline()).build();
		passThruSubscription = registerElemErrListener(passThru);
	}

	@Override
	public synchronized void unregisterErrorListeners() {
		super.unregisterErrorListeners();
		unregisterElementErrListener(passThru, passThruSubscription);
		for (String elemId : elementIds)
			unregisterElementErrListener(elements.get(elemId),
					elementsErrorSubscriptions.remove(elemId));
	}

	/**
	 * @return all media elements created for this publisher, except for the
	 *         main element ({@link WebRtcEndpoint})
	 */
	public synchronized Collection<MediaElement> getMediaElements() {
		if (passThru != null)
			elements.put(passThru.getId(), passThru);
		return elements.values();
	}

	public synchronized String publish(String sdpOffer) {
		registerOnIceCandidateEventListener();
		connect(endpoint); // loopback
		String sdpAnswer = processOffer(sdpOffer);
		gatherCandidates();
		return sdpAnswer;
	}

	public synchronized void connect(MediaElement other) {
		if (!connected)
			innerConnect();
		passThru.connect(other);
	}

	@Override
	public synchronized String apply(MediaElement shaper) throws RoomException {
		String id = shaper.getId();
		if (elements.containsKey(id))
			throw new RoomException(Code.WEBRTC_ENDPOINT_ERROR_CODE,
					"This endpoint already has a media element with id " + id);
		MediaElement first = null;
		if (!elementIds.isEmpty())
			first = elements.get(elementIds.getFirst());
		if (connected) {
			if (first != null)
				first.connect(shaper);
			else
				endpoint.connect(shaper);
			shaper.connect(passThru);
		}
		elementIds.addFirst(id);
		elements.put(id, shaper);
		elementsErrorSubscriptions.put(id, registerElemErrListener(shaper));
		return id;
	}

	@Override
	public synchronized void revert(MediaElement shaper) throws RoomException {
		String elementId = shaper.getId();
		if (!elements.containsKey(elementId))
			throw new RoomException(Code.WEBRTC_ENDPOINT_ERROR_CODE,
					"This endpoint has no media element with id " + elementId);

		MediaElement element = elements.get(elementId);
		unregisterElementErrListener(element,
				elementsErrorSubscriptions.remove(elementId));
		// TODO do it inside a transaction??
		element.release();
		if (!connected)
			return;

		String nextId = getNext(elementId);
		String prevId = getPrevious(elementId);
		// next connects to prev
		MediaElement prev = null;
		MediaElement next = null;
		if (nextId != null)
			next = elements.get(nextId);
		else
			next = endpoint;
		if (prevId != null)
			prev = elements.get(prevId);
		else
			prev = passThru;
		next.connect(prev);
	}

	private String getNext(String uid) {
		int idx = elementIds.indexOf(uid);
		if (idx < 0 || idx + 1 == elementIds.size())
			return null;
		return elementIds.get(idx + 1);
	}

	private String getPrevious(String uid) {
		int idx = elementIds.indexOf(uid);
		if (idx <= 0)
			return null;
		return elementIds.get(idx - 1);
	}

	private void innerConnect() {
		if (endpoint == null)
			throw new RoomException(Code.WEBRTC_ENDPOINT_ERROR_CODE,
					"Can't connect null WebRtcEndpoint");
		MediaElement current = endpoint;
		String prevId = elementIds.peekLast();
		while (prevId != null) {
			MediaElement prev = elements.get(prevId);
			if (prev == null)
				throw new RoomException(Code.WEBRTC_ENDPOINT_ERROR_CODE,
						"No media element with id " + prevId);
			current.connect(prev);
			current = prev;
			prevId = getPrevious(prevId);
		}
		current.connect(passThru);
		connected = true;
	}
}
