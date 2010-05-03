/*
 * Copyright 2002-2010 the original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package org.springframework.integration.aggregator;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.integration.channel.ChannelResolver;
import org.springframework.integration.channel.MessageChannelTemplate;
import org.springframework.integration.channel.NullChannel;
import org.springframework.integration.core.Message;
import org.springframework.integration.core.MessageChannel;
import org.springframework.integration.core.MessageHeaders;
import org.springframework.integration.core.MessageProducer;
import org.springframework.integration.handler.AbstractMessageHandler;
import org.springframework.integration.message.MessageBuilder;
import org.springframework.integration.store.MessageGroup;
import org.springframework.integration.store.MessageGroupStore;
import org.springframework.integration.store.MessageStore;
import org.springframework.integration.store.SimpleMessageStore;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.util.Assert;

/**
 * Message handler that holds a buffer of correlated messages in a {@link MessageStore}. This class takes care of
 * correlated groups of messages that can be completed in batches. It is useful for aggregating, resequencing, or custom
 * implementations requiring correlation.
 * <p/>
 * To customize this handler inject {@link CorrelationStrategy}, {@link ReleaseStrategy}, and
 * {@link MessageGroupProcessor} implementations as you require.
 * <p/>
 * By default the CorrelationStrategy will be a HeaderAttributeCorrelationStrategy and the ReleaseStrategy will be a
 * SequenceSizeReleaseStrategy.
 * 
 * @author Iwein Fuld
 * @author Dave Syer
 * @since 2.0
 */
public class CorrelatingMessageHandler extends AbstractMessageHandler implements MessageProducer {

	private static final Log logger = LogFactory.getLog(CorrelatingMessageHandler.class);

	public static final long DEFAULT_SEND_TIMEOUT = 1000L;

	public static final long DEFAULT_REAPER_INTERVAL = 1000L;

	public static final long DEFAULT_TIMEOUT = 60000L;

	private final MessageGroupStore store;

	private final MessageGroupProcessor outputProcessor;

	private volatile CorrelationStrategy correlationStrategy;

	private volatile ReleaseStrategy releaseStrategy;

	private MessageChannel outputChannel;

	private final MessageChannelTemplate channelTemplate = new MessageChannelTemplate();

	private volatile MessageChannel discardChannel = new NullChannel();

	private boolean sendPartialResultOnTimeout = false;

	private final ConcurrentMap<Object, Object> locks = new ConcurrentHashMap<Object, Object>();

	public CorrelatingMessageHandler(MessageGroupProcessor processor, MessageGroupStore store,
			CorrelationStrategy correlationStrategy, ReleaseStrategy releaseStrategy) {
		Assert.notNull(store);
		Assert.notNull(processor);
		this.store = store;
		this.outputProcessor = processor;
		this.correlationStrategy = correlationStrategy == null ? new HeaderAttributeCorrelationStrategy(
				MessageHeaders.CORRELATION_ID) : correlationStrategy;
		this.releaseStrategy = releaseStrategy == null ? new SequenceSizeReleaseStrategy() : releaseStrategy;
		this.channelTemplate.setSendTimeout(DEFAULT_SEND_TIMEOUT);
	}

	public CorrelatingMessageHandler(MessageGroupProcessor processor, MessageGroupStore store) {
		this(processor, store, null, null);
	}

	public CorrelatingMessageHandler(MessageGroupProcessor processor) {
		this(processor, new SimpleMessageStore(0), null, null);
	}

	public void setCorrelationStrategy(CorrelationStrategy correlationStrategy) {
		Assert.notNull(correlationStrategy);
		this.correlationStrategy = correlationStrategy;
	}

	public void setReleaseStrategy(ReleaseStrategy releaseStrategy) {
		Assert.notNull(releaseStrategy);
		this.releaseStrategy = releaseStrategy;
	}

	public void setTaskScheduler(TaskScheduler taskScheduler) {
		super.setTaskScheduler(taskScheduler);
	}

	// TODO: remove unused property setters
	public void setTimeout(long timeout) {
	}

	public void setReaperInterval(long reaperInterval) {
	}

	public void setOutputChannel(MessageChannel outputChannel) {
		Assert.notNull(outputChannel, "'outputChannel' must not be null");
		this.outputChannel = outputChannel;
	}

	public void setChannelResolver(ChannelResolver channelResolver) {
		super.setChannelResolver(channelResolver);
	}

	public void setDiscardChannel(MessageChannel discardChannel) {
		this.discardChannel = discardChannel;
	}

	public void setSendTimeout(long sendTimeout) {
		this.channelTemplate.setSendTimeout(sendTimeout);
	}

	public void setSendPartialResultOnTimeout(boolean sendPartialResultOnTimeout) {
		this.sendPartialResultOnTimeout = sendPartialResultOnTimeout;
	}

	@Override
	public String getComponentType() {
		return "aggregator";
	}

	@Override
	protected void handleMessageInternal(Message<?> message) throws Exception {

		Object correlationKey = correlationStrategy.getCorrelationKey(message);
		if (logger.isDebugEnabled()) {
			logger.debug("Handling message with correlationKey [" + correlationKey + "]: " + message);
		}

		if (!correlationKey.equals(message.getHeaders().getCorrelationId())) {
			// TODO: strategise the treatment of overwritten correlation
			message = MessageBuilder.fromMessage(message).setCorrelationId(correlationKey).build();
		}

		// TODO: make the lock global?
		Object lock = getLock(correlationKey);
		synchronized (lock) {

			MessageGroup group = store.getMessageGroup(correlationKey);

			if (group.add(message)) {

				store(correlationKey, message);

				if (releaseStrategy.canRelease(group)) {

					if (logger.isDebugEnabled()) {
						logger.debug("Completing group with correlationKey [" + correlationKey + "]");
					}
					outputProcessor.processAndSend(group, channelTemplate, this.resolveReplyChannel(message,
							this.outputChannel));
					if (group.isComplete() || group.getSequenceSize() == 0) {
						// The group is complete or else there is no sequence so there is no more state to track
						remove(group);
					}
					else {
						// Mark these messages as processed, but do not remove the group from store
						mark(group);
					}

				}
				else if (group.isComplete()) {

					// If not releasing any messages the group might still be complete
					for (Message<?> discard : group.getUnmarked()) {
						discardChannel.send(discard);
					}
					remove(group);

				}

			}
			else {
				discardChannel.send(message);
			}

		}

	}

	// TODO: arrange for this to be called if user desires, e.g. periodically
	public final boolean forceComplete(Object correlationKey) {

		Object lock = getLock(correlationKey);
		synchronized (lock) {

			MessageGroup group = store.getMessageGroup(correlationKey);
			if (group.size() > 0) {
				// last chance for normal completion
				if (releaseStrategy.canRelease(group)) {
					outputProcessor.processAndSend(group, channelTemplate, resolveReplyChannel(group.getOne(),
							this.outputChannel));
					remove(group);
				}
				else {
					if (sendPartialResultOnTimeout) {
						if (logger.isInfoEnabled()) {
							logger.info("Processing partially complete messages for key [" + correlationKey + "] to: "
									+ outputChannel);
						}
						outputProcessor.processAndSend(group, channelTemplate, resolveReplyChannel(group.getOne(),
								this.outputChannel));
					}
					else {
						if (logger.isInfoEnabled()) {
							logger.info("Discarding partially complete messages for key [" + correlationKey + "] to: "
									+ discardChannel);
						}
						for (Message<?> message : group.getUnmarked()) {
							discardChannel.send(message);
						}
					}
					remove(group);
				}
				return true;
			}
			return false;
		}
	}

	private Object getLock(Object correlationKey) {
		locks.putIfAbsent(correlationKey, correlationKey);
		return locks.get(correlationKey);
	}

	private void mark(MessageGroup group) {
		store.mark(group);
	}

	private void remove(MessageGroup group) {
		Object correlationKey = group.getCorrelationKey();
		store.deleteMessageGroup(correlationKey);
		locks.remove(correlationKey);
	}

	private void store(Object correlationKey, Message<?> message) {
		store.addMessageToGroup(correlationKey, message);
	}

}
