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

import java.util.LinkedList;

import org.kurento.client.ErrorEvent;
import org.kurento.client.EventListener;
import org.kurento.client.IceCandidate;
import org.kurento.client.ListenerSubscription;
import org.kurento.client.MediaElement;
import org.kurento.client.MediaPipeline;
import org.kurento.client.OnIceCandidateEvent;
import org.kurento.client.WebRtcEndpoint;
import org.kurento.commons.exception.KurentoException;
import org.kurento.room.exception.RoomException;
import org.kurento.room.exception.RoomException.Code;
import org.kurento.room.internal.Participant;

/**
 * {@link WebRtcEndpoint} wrapper that supports buffering of
 * {@link IceCandidate}s until the {@link WebRtcEndpoint} is created.
 * Connections to other peers are opened using the corresponding method of the
 * internal endpoint.
 * 
 * @author <a href="mailto:rvlad@naevatec.com">Radu Tom Vlad</a>
 */
public abstract class IceWebRtcEndpoint {

	private Participant owner;
	private String endpointName;

	private MediaPipeline pipeline = null;
	protected WebRtcEndpoint endpoint = null;
	private ListenerSubscription endpointSubscription = null;

	private LinkedList<IceCandidate> candidates =
			new LinkedList<IceCandidate>();

	/**
	 * Constructor to set the owner, the endpoint's name and the media pipeline.
	 * 
	 * @param owner
	 * @param endpointName
	 * @param pipeline
	 */
	public IceWebRtcEndpoint(Participant owner, String endpointName,
			MediaPipeline pipeline) {
		this.owner = owner;
		this.setEndpointName(endpointName);
		this.setMediaPipeline(pipeline);
	}

	/**
	 * @return the user session that created this endpoint
	 */
	public Participant getOwner() {
		return owner;
	}

	/**
	 * @return the internal {@link WebRtcEndpoint}
	 */
	public WebRtcEndpoint getEndpoint() {
		return endpoint;
	}

	/**
	 * If this object doesn't have a {@link WebRtcEndpoint}, it is created in a
	 * thread-safe way using the internal {@link MediaPipeline}. Otherwise no
	 * actions are taken. It also registers an error listener for the endpoint
	 * and for any additional media elements.
	 * 
	 * @return the existing endpoint, if any
	 */
	public synchronized WebRtcEndpoint createEndpoint() {
		WebRtcEndpoint old = this.endpoint;
		if (this.endpoint == null)
			internalEndpointInitialization();
		while (!candidates.isEmpty())
			this.endpoint.addIceCandidate(candidates.removeFirst());
		return old;
	}

	/**
	 * @return the pipeline
	 */
	public MediaPipeline getPipeline() {
		return this.pipeline;
	}

	/**
	 * Sets the {@link MediaPipeline} used to create the internal
	 * {@link WebRtcEndpoint}.
	 * 
	 * @param pipeline the {@link MediaPipeline}
	 */
	public void setMediaPipeline(MediaPipeline pipeline) {
		this.pipeline = pipeline;
	}

	/**
	 * @return name of this endpoint (as indicated by the browser)
	 */
	public String getEndpointName() {
		return endpointName;
	}

	/**
	 * Sets the endpoint's name (as indicated by the browser).
	 * 
	 * @param endpointName the name
	 */
	public void setEndpointName(String endpointName) {
		this.endpointName = endpointName;
	}

	/**
	 * Add a new {@link IceCandidate} received gathered by the remote peer of
	 * this {@link WebRtcEndpoint}.
	 * 
	 * @param candidate the remote candidate
	 */
	public synchronized void addIceCandidate(IceCandidate candidate) {
		if (endpoint == null)
			candidates.addLast(candidate);
		else
			endpoint.addIceCandidate(candidate);
	}

	/**
	 * Unregisters all error listeners created for media elements owned by this
	 * instance.
	 */
	public synchronized void unregisterErrorListeners() {
		unregisterElementErrListener(endpoint, endpointSubscription);
	}

	/**
	 * Create the endpoint and any other additional elements (if needed).
	 */
	protected void internalEndpointInitialization() {
		this.endpoint = new WebRtcEndpoint.Builder(pipeline).build();
		this.endpointSubscription = registerElemErrListener(this.endpoint);
	}

	/**
	 * Registers a listener for when the {@link WebRtcEndpoint} gathers a new
	 * {@link IceCandidate} and sends it to the remote User Agent as a
	 * notification using the messaging capabilities of the {@link Participant}.
	 * 
	 * @see WebRtcEndpoint#addOnIceCandidateListener(org.kurento.client.EventListener)
	 * @see Participant#sendIceCandidate(String, IceCandidate)
	 * @throws RoomException if thrown, unable to register the listener
	 */
	protected void registerOnIceCandidateEventListener() throws RoomException {
		if (endpoint == null)
			throw new RoomException(Code.WEBRTC_ENDPOINT_ERROR_CODE,
					"Can't register event listener for null WebRtcEndpoint");
		endpoint.addOnIceCandidateListener(new EventListener<OnIceCandidateEvent>() {
			@Override
			public void onEvent(OnIceCandidateEvent event) {
				owner.sendIceCandidate(endpointName, event.getCandidate());
			}
		});
	}

	/**
	 * Registers a listener for when the {@link MediaElement} triggers an
	 * {@link ErrorEvent}. Notifies the owner with the error.
	 * 
	 * @param element the {@link MediaElement}
	 * @return {@link ListenerSubscription} that can be used to deregister the
	 *         listener
	 */
	protected ListenerSubscription registerElemErrListener(MediaElement element) {
		return element.addErrorListener(new EventListener<ErrorEvent>() {
			@Override
			public void onEvent(ErrorEvent event) {
				owner.sendMediaError(event);
			}
		});
	}

	/**
	 * Unregisters the error listener from the media element using the provided
	 * subscription.
	 * 
	 * @param element the {@link MediaElement}
	 * @param subscription the associated {@link ListenerSubscription}
	 */
	protected void unregisterElementErrListener(MediaElement element,
			ListenerSubscription subscription) {
		if (element == null || subscription == null)
			return;
		element.removeErrorListener(subscription);
	}

	/**
	 * Orders the internal {@link WebRtcEndpoint} to process the offer String.
	 * 
	 * @see WebRtcEndpoint#processOffer(String)
	 * @param offer String with the Sdp offer
	 * @return the Sdp answer
	 */
	protected String processOffer(String offer) {
		if (endpoint == null)
			throw new KurentoException(
					"Can't process offer when WebRtcEndpoint is null");
		return endpoint.processOffer(offer);
	}

	/**
	 * Instructs the internal {@link WebRtcEndpoint} to start gathering
	 * {@link IceCandidate}s.
	 */
	protected void gatherCandidates() {
		if (endpoint == null)
			throw new KurentoException(
					"Can't start gathering ICE candidates on null WebRtcEndpoint");
		endpoint.gatherCandidates();
	}
}
