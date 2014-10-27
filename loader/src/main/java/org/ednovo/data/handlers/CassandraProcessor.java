/*******************************************************************************
 * CassandraProcessor.java
 * insights-event-logger
 * Created by Gooru on 2014
 * Copyright (c) 2014 Gooru. All rights reserved.
 * http://www.goorulearning.org/
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 ******************************************************************************/
package org.ednovo.data.handlers;

import java.text.ParseException;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.ednovo.data.model.EventData;
import org.ednovo.data.model.EventObject;
import org.ednovo.data.model.EventObjectValidator;
import org.json.JSONException;
import org.logger.event.cassandra.loader.CassandraDataLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

public class CassandraProcessor extends BaseDataProcessor implements DataProcessor {
	static final Logger logger = LoggerFactory.getLogger(CassandraProcessor.class);
	
	protected CassandraDataLoader dataLoader;
	private Gson gson;
	private final String GOORU_EVENT_LOGGER_API_KEY = "b6b82f4d-0e6e-4ad5-96d9-30849cf17727";
	private final String EVENT_SOURCE = "file-logged";
	private EventObjectValidator eventObjectValidator;
	
	public CassandraProcessor(Map<String,String> configOptionsMap){
		
		gson = new Gson();
		dataLoader = new CassandraDataLoader(configOptionsMap);
		eventObjectValidator = new EventObjectValidator(configOptionsMap);
	}

        @Override
        public void handleRow(Object row) throws Exception {

         if (row != null && (row instanceof EventData)) {
       
            EventData eventData = (EventData) row;
            /*
             * Skip new version  events  
             */
            if(eventData.getVersion() != null){
            	return;
            }
            logger.info("EventName : {}",eventData.getEventName());
            cleanseData(eventData);

            if (eventData.getEventName() == null || eventData.getEventName().isEmpty()) {
            	return;
            }
            // Update into Cassandra through logger-core
            String fields = eventData.getFields();
            if (fields == null || fields.isEmpty()) {
                logger.warn("fields is empty. This is an error");
                return;
            }
            
    		if ( "Add%20Segment%20Name".equalsIgnoreCase(eventData.getQuery()) ||  "*".equalsIgnoreCase(eventData.getQuery())){
    			return;
    		}  
            eventData.setApiKey(GOORU_EVENT_LOGGER_API_KEY);
            eventData.setEventSource(EVENT_SOURCE);
            dataLoader.handleLogMessage(eventData);
            
            handleRowByNextHandler(eventData);
        }
         if (row != null && (row instanceof EventObject)) {
        	
        	 EventObject eventObject = (EventObject) row;
         	
        	 if(eventObject.getVersion() == null){
             	return;
             }
        	 
        	if (eventObject.getEventName() == null || eventObject.getEventName().isEmpty() || eventObject.getContext() == null) {
        		logger.warn("EventName or Context is empty. This is an error in EventObject");
        		return;
         	}

        	eventObjectValidator.validateEventObject(eventObject);
         	dataLoader.handleEventObjectMessage(eventObject);
         }
	}
        
        public void updateToStaging(String startTime,String endTime,String eventName,String apiKey,String methodName){
        	try {
        		if(methodName.equalsIgnoreCase("staging")){        			
        			dataLoader.updateStaging(startTime, endTime,eventName,apiKey);
        		}
        		if(methodName.equalsIgnoreCase("activity")){        			
        			dataLoader.updateStagingES(startTime, endTime,eventName,apiKey);
        		}
        		if(methodName.equalsIgnoreCase("catalog")){        			
        			dataLoader.catalogMigration(startTime, endTime, eventName);
        		}
        		if(methodName.equalsIgnoreCase("counter")){        			
        			dataLoader.catalogMigration(startTime, endTime, eventName);
        		}
			} catch (ParseException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
        }

        public void postAggregation(String statTime,String endTime,String eventName){
				dataLoader.postMigration(statTime, endTime,eventName);
        }

        private void cleanseData(EventData eventData) {
		updateEventNameForConsistency(eventData);
		updateEventNameIfEmpty(eventData);
		cleansEventValue(eventData);
		if(eventData.getStartTime() == null && eventData.getExpireTime() != null) {
			// SessionExpired event did NOT have start / end time. Use expiry time.
			eventData.setStartTime(eventData.getExpireTime());
			eventData.setEndTime(eventData.getExpireTime());
		}
	}
	private void cleansEventValue(EventData eventData){
	
			String eventValue = eventData.getQuery();
			if(eventValue != null) {
			 if (eventValue.startsWith("\'") || eventValue.startsWith("\"")) {
				eventValue = eventValue.substring(1, eventValue.length());
		      }
		      if (eventValue.endsWith("\'") || eventValue.endsWith("\"")) {
		    	  eventValue = eventValue.substring(0, eventValue.length() - 1);
		      }
		      eventValue = eventValue.trim();
		      if(eventValue.length() > 100){
		    	  eventValue = eventValue.substring(0, 100);
		      }
		      eventData.setQuery(eventValue);
			}
	}
	
	
	private void updateEventNameForConsistency(EventData eventData) {
		//Handle event naming convention inconsistency - ensure event map is not empty
		String eventName = DataUtils.makeEventNameConsistent(eventData.getEventName());
		
		
		if(eventName != null) {
			if(eventName.equalsIgnoreCase("collection-play") && !StringUtils.isEmpty(eventData.getParentGooruId())) {
				// This is actually collection-resource-play event, coming up disguised as collection-play. Change.
				eventName = "collection-resource-play";
			}
 		}
		eventData.setEventName(eventName);
	}

	private void updateEventNameIfEmpty(EventData eventData) {
		 if (StringUtils.isEmpty(eventData.getEventName()) && eventData.getContext() != null){
	     		Map<String, String> guessedAttributes = DataUtils.guessEventName(eventData.getContext());
	     		if(guessedAttributes != null) {
	     			if(guessedAttributes.get("event_name") != null ) {
	     				eventData.setEventName(guessedAttributes.get("event_name"));
	     			}
	     			if(guessedAttributes.get("parent_gooru_id") != null  && eventData.getParentGooruId() == null) {
	     				eventData.setParentGooruId(guessedAttributes.get("parent_gooru_id"));
	     			}
	     			if(guessedAttributes.get("content_gooru_id") != null  && eventData.getContentGooruId() == null) {
	     				eventData.setContentGooruId(guessedAttributes.get("content_gooru_id"));
	     			}
	     		}
			 }
	}
	
}
