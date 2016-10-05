/**
 * Copyright 2016 Ambud Sharma
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * 		http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.srotya.tau.nucleus.processor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.lmax.disruptor.EventHandler;
import com.srotya.tau.nucleus.DisruptorUnifiedFactory;
import com.srotya.tau.nucleus.disruptor.ShuffleHandler;
import com.srotya.tau.wraith.Constants;
import com.srotya.tau.wraith.Event;
import com.srotya.tau.wraith.MutableInt;
import com.srotya.tau.wraith.actions.Action;
import com.srotya.tau.wraith.rules.Rule;
import com.srotya.tau.wraith.rules.RulesEngineCaller;
import com.srotya.tau.wraith.rules.StatelessRulesEngine;

/**
 * @author ambudsharma
 *
 */
public class RuleProcessor extends AbstractProcessor {

	public RuleProcessor(DisruptorUnifiedFactory factory, int parallelism, int bufferSize, Map<String, String> conf,
			AbstractProcessor... outputProcessors) {
		super(factory, parallelism, bufferSize, conf, outputProcessors);
	}

	@Override
	public List<EventHandler<Event>> getInitializedHandlers(MutableInt parallelism, Map<String, String> conf,
			DisruptorUnifiedFactory factory) throws Exception {
		List<EventHandler<Event>> handlers = new ArrayList<>();
		for (int i = 0; i < parallelism.getVal(); i++) {
			RulesEngineHandler handler = new RulesEngineHandler(this, i, parallelism, factory, getOutputProcessors());
			handler.init(conf);
			handlers.add(handler);
		}
		return handlers;
	}

	public static class RulesEngineHandler extends ShuffleHandler implements RulesEngineCaller<Object, Object> {

		private static final Logger logger = Logger.getLogger(RulesEngineHandler.class.getName());
		private StatelessRulesEngine<Object, Object> rulesEngine;
		private DisruptorUnifiedFactory factory;
		private AbstractProcessor caller;
		private AbstractProcessor alertProcessor;
		private AbstractProcessor stateProcessor;
		private AbstractProcessor aggregationProcessor;
		private Gson gson;

		/**
		 * 
		 * @param caller
		 * @param taskId
		 * @param taskCount
		 * @param factory
		 * @param outputProcessors
		 *            Expected index: 0 - Alert, 1 - State, 2 - Aggregation
		 */
		public RulesEngineHandler(AbstractProcessor caller, int taskId, MutableInt taskCount,
				DisruptorUnifiedFactory factory, AbstractProcessor... outputProcessors) {
			super(taskId, taskCount);
			this.caller = caller;
			this.factory = factory;
			this.rulesEngine = new StatelessRulesEngine<>(this, factory, factory);
			this.gson = new Gson();
			initProcessors(outputProcessors);
		}

		private void initProcessors(AbstractProcessor[] outputProcessors) {
			if (outputProcessors != null && outputProcessors.length >= 1) {
				alertProcessor = outputProcessors[0];
			}
			if (outputProcessors.length >= 2) {
				stateProcessor = outputProcessors[1];
			}
			if (outputProcessors.length >= 3) {
				aggregationProcessor = outputProcessors[2];
			}
		}

		public void init(Map<String, String> conf) throws Exception {
			rulesEngine.initialize(conf);
		}

		@Override
		public void consumeEvent(Event event, long sequence, boolean endOfBatch) throws Exception {
			Object type = event.getHeaders().get(Constants.FIELD_EVENT_TYPE);
			if (type!=null && type.equals(Constants.EVENT_TYPE_RULE_UPDATE)) {
				rulesEngine.updateRule(event.getHeaders().get(Constants.FIELD_RULE_GROUP).toString(),
						event.getHeaders().get(Constants.FIELD_RULE_CONTENT).toString(),
						((Boolean) event.getHeaders().get(Constants.FIELD_RULE_DELETE)));
				logger.info("Processed rule update:" + event.getHeaders().get(Constants.FIELD_RULE_CONTENT).toString());
			} else {
				rulesEngine.evaluateEventAgainstGroupedRules(null, null, event);
				caller.ackEvent(event.getEventId());
			}
		}

		@Override
		public void emitActionErrorEvent(Object eventCollector, Object eventContainer, Event actionErrorEvent) {
			// TODO Auto-generated method stub

		}

		@Override
		public void emitRawAlert(Object eventCollector, Object eventContainer, Event outputEvent, Short ruleId,
				Short actionId, String target, String mediaType) {
			// NOT SUPPORTED
		}

		@Override
		public void emitTemplatedAlert(Object eventCollector, Object eventContainer, Event outputEvent, String ruleGroup, Short ruleId,
				Short actionId, String ruleName, Short templateId, Long timestamp) {
			// alert
			Event event = factory.buildEvent();
			event.getHeaders().put(Constants.FIELD_EVENT, outputEvent);
			event.getHeaders().put(Constants.FIELD_TIMESTAMP, timestamp);
			event.getHeaders().put(Constants.FIELD_ALERT_TEMPLATE_ID, templateId);
			event.getHeaders().put(Constants.FIELD_ACTION_ID, actionId);
			event.getHeaders().put(Constants.FIELD_RULE_ID, ruleId);
			event.getHeaders().put(Constants.FIELD_RULE_NAME, ruleName);
			event.getHeaders().put(Constants.FIELD_RULE_GROUP, ruleGroup);
			event.setEventId(outputEvent.getEventId());
			event.setBody(gson.toJson(event.getHeaders()).getBytes());
			try {
				alertProcessor.processEventWaled(event);
			} catch (IOException e) {
				emitActionErrorEvent(eventCollector, eventContainer, outputEvent);
			}
		}

		@Override
		public void handleRuleNoMatch(Object eventCollector, Object eventContainer, Event inputEvent, Rule rule) {
			// TODO Auto-generated method stub

		}

		@Override
		public void reportRuleHit(String ruleGroup, short ruleId) {
			// TODO Auto-generated method stub

		}

		@Override
		public void reportRuleEfficiency(String ruleGroup, short ruleId, long executeTime) {
			// TODO Auto-generated method stub

		}

		@Override
		public void reportConditionEfficiency(String ruleGroup, short ruleId, long executeTime) {
			// TODO Auto-generated method stub

		}

		@Override
		public void emitAggregationEvent(Class<? extends Action> action, Object eventCollector, Object eventContainer,
				Event originalEvent, Long timestamp, int windowSize, String ruleActionId, String aggregationKey,
				Object aggregationValue) {
			// TODO Auto-generated method stub

		}

		@Override
		public void emitStateTrackingEvent(Object eventCollector, Object eventContainer, Boolean track,
				Event originalEvent, Long timestamp, int windowSize, String ruleActionId, String aggregationKey) {
			// TODO Auto-generated method stub

		}

		@Override
		public void emitNewEvent(Object eventCollector, Object eventContainer, Event originalEvent, Event outputEvent) {
			// TODO Auto-generated method stub

		}

		@Override
		public void emitTaggedEvent(Object eventCollector, Object eventContainer, Event outputEvent) {
			// TODO Auto-generated method stub

		}

		@Override
		public void emitOmegaActions(Object eventCollector, Object eventContainer, Event outputEvent) {
			// TODO Auto-generated method stub

		}

		@Override
		public void emitAnomalyAction(Object eventCollector, Object eventContainer, String seriesName, Number value) {
			// TODO Auto-generated method stub

		}

	}

	@Override
	public String getConfigPrefix() {
		return "rules";
	}

}
