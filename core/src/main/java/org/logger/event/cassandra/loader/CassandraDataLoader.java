/*******************************************************************************
 * CassandraDataLoader.java
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
package org.logger.event.cassandra.loader;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.ednovo.data.geo.location.GeoLocation;
import org.ednovo.data.model.EventData;
import org.ednovo.data.model.EventObject;
import org.ednovo.data.model.JSONDeserializer;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.kafka.event.microaggregator.producer.MicroAggregatorProducer;
import org.kafka.log.writer.producer.KafkaLogProducer;
import org.logger.event.cassandra.loader.dao.BaseCassandraRepoImpl;
import org.logger.event.cassandra.loader.dao.BaseDAOCassandraImpl;
import org.logger.event.cassandra.loader.dao.LiveDashBoardDAOImpl;
import org.logger.event.cassandra.loader.dao.MicroAggregatorDAOmpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.access.AccessDeniedException;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.maxmind.geoip2.exception.GeoIp2Exception;
import com.netflix.astyanax.Keyspace;
import com.netflix.astyanax.MutationBatch;
import com.netflix.astyanax.connectionpool.exceptions.ConnectionException;
import com.netflix.astyanax.model.Column;
import com.netflix.astyanax.model.ColumnList;
import com.netflix.astyanax.model.ConsistencyLevel;
import com.netflix.astyanax.model.Row;
import com.netflix.astyanax.model.Rows;
import com.netflix.astyanax.util.TimeUUIDUtils;

import flexjson.JSONSerializer;

public class CassandraDataLoader  implements Constants {

    private static final Logger logger = LoggerFactory.getLogger(CassandraDataLoader.class);
    
    private Keyspace cassandraKeyspace;
    
    private static final ConsistencyLevel DEFAULT_CONSISTENCY_LEVEL = ConsistencyLevel.CL_ONE;
    
    private SimpleDateFormat minuteDateFormatter;
    
    private SimpleDateFormat dateFormatter;
    
    static final long NUM_100NS_INTERVALS_SINCE_UUID_EPOCH = 0x01b21dd213814000L;
    
    private CassandraConnectionProvider connectionProvider;
    
    private KafkaLogProducer kafkaLogWriter;
  
    private MicroAggregatorDAOmpl liveAggregator;
    
    private LiveDashBoardDAOImpl liveDashBoardDAOImpl;
    
    public static  Map<String,String> cache;
    
    public static  Map<String,Object> licenseCache;
    
    public static  Map<String,Object> resourceTypesCache;
    
    public static  Map<String,Object> categoryCache;
    
    public static  Map<String,Object> gooruTaxonomy;
    
    private MicroAggregatorProducer microAggregator;
    
    private static GeoLocation geo;
    
    public Collection<String> pushingEvents ;
    
    public Collection<String> statKeys ;
    
    public ColumnList<String> statMetrics ;
        
    private BaseCassandraRepoImpl baseDao ;
    
    /**
     * Get Kafka properties from Environment
     */
    public CassandraDataLoader() {
        this(null);
        
        //micro Aggregator producer IP
        String KAFKA_AGGREGATOR_PRODUCER_IP = System.getenv("INSIGHTS_KAFKA_AGGREGATOR_PRODUCER_IP");
        //Log Writter producer IP
        String KAFKA_LOG_WRITTER_PRODUCER_IP = System.getenv("INSIGHTS_KAFKA_LOG_WRITTER_PRODUCER_IP");
        String KAFKA_PORT = System.getenv("INSIGHTS_KAFKA_PORT");
        String KAFKA_ZK_PORT = System.getenv("INSIGHTS_KAFKA_ZK_PORT");
        String KAFKA_TOPIC = System.getenv("INSIGHTS_KAFKA_TOPIC");
        String KAFKA_FILE_TOPIC = System.getenv("INSIGHTS_KAFKA_FILE_TOPIC");
        String KAFKA_AGGREGATOR_TOPIC = System.getenv("INSIGHTS_KAFKA_AGGREGATOR_TOPIC");
        String KAFKA_PRODUCER_TYPE = System.getenv("INSIGHTS_KAFKA_PRODUCER_TYPE");
        
        kafkaLogWriter = new KafkaLogProducer(KAFKA_LOG_WRITTER_PRODUCER_IP, KAFKA_ZK_PORT,  KAFKA_FILE_TOPIC, KAFKA_PRODUCER_TYPE);
        microAggregator = new MicroAggregatorProducer(KAFKA_AGGREGATOR_PRODUCER_IP, KAFKA_ZK_PORT,  KAFKA_AGGREGATOR_TOPIC, KAFKA_PRODUCER_TYPE);
    }

    public CassandraDataLoader(Map<String, String> configOptionsMap) {
        init(configOptionsMap);
        //micro Aggregator producer IP
        String KAFKA_AGGREGATOR_PRODUCER_IP = System.getenv("INSIGHTS_KAFKA_AGGREGATOR_PRODUCER_IP");
        //Log Writter producer IP
        String KAFKA_LOG_WRITTER_PRODUCER_IP = System.getenv("INSIGHTS_KAFKA_LOG_WRITTER_PRODUCER_IP");
        String KAFKA_PORT = System.getenv("INSIGHTS_KAFKA_PORT");
        String KAFKA_ZK_PORT = System.getenv("INSIGHTS_KAFKA_ZK_PORT");
        String KAFKA_TOPIC = System.getenv("INSIGHTS_KAFKA_TOPIC");
        String KAFKA_FILE_TOPIC = System.getenv("INSIGHTS_KAFKA_FILE_TOPIC");
        String KAFKA_AGGREGATOR_TOPIC = System.getenv("INSIGHTS_KAFKA_AGGREGATOR_TOPIC");
        String KAFKA_PRODUCER_TYPE = System.getenv("INSIGHTS_KAFKA_PRODUCER_TYPE");
        
        microAggregator = new MicroAggregatorProducer(KAFKA_AGGREGATOR_PRODUCER_IP, KAFKA_ZK_PORT,  KAFKA_AGGREGATOR_TOPIC, KAFKA_PRODUCER_TYPE);
        kafkaLogWriter = new KafkaLogProducer(KAFKA_LOG_WRITTER_PRODUCER_IP, KAFKA_ZK_PORT,  KAFKA_FILE_TOPIC, KAFKA_PRODUCER_TYPE);
    }

    public static long getTimeFromUUID(UUID uuid) {
        return (uuid.timestamp() - NUM_100NS_INTERVALS_SINCE_UUID_EPOCH) / 10000;
    }

    /**
     * *
     * @param configOptionsMap
     * Initialize CoulumnFamily
     */
    
    private void init(Map<String, String> configOptionsMap) {
    	
        this.minuteDateFormatter = new SimpleDateFormat("yyyyMMddkkmm");
        this.dateFormatter = new SimpleDateFormat("yyyy-MM-dd kk:mm:ss");
        
        this.setConnectionProvider(new CassandraConnectionProvider());
        this.getConnectionProvider().init(configOptionsMap);

        this.liveAggregator = new MicroAggregatorDAOmpl(getConnectionProvider());
        this.liveDashBoardDAOImpl = new LiveDashBoardDAOImpl(getConnectionProvider());
        baseDao = new BaseCassandraRepoImpl(getConnectionProvider());

        Rows<String, String> operators = baseDao.readAllRows(ColumnFamily.REALTIMECONFIG.getColumnFamily());
        cache = new LinkedHashMap<String, String>();
        for (Row<String, String> row : operators) {
        	cache.put(row.getKey(), row.getColumns().getStringValue("aggregator_json", null));
		}
        cache.put(VIEWEVENTS, baseDao.readWithKeyColumn(ColumnFamily.CONFIGSETTINGS.getColumnFamily(), "views~events", DEFAULTCOLUMN).getStringValue());
        cache.put(ATMOSPHERENDPOINT, baseDao.readWithKeyColumn(ColumnFamily.CONFIGSETTINGS.getColumnFamily(), "atmosphere.end.point", DEFAULTCOLUMN).getStringValue());
        cache.put(VIEWUPDATEENDPOINT, baseDao.readWithKeyColumn(ColumnFamily.CONFIGSETTINGS.getColumnFamily(), LoaderConstants.VIEW_COUNT_REST_API_END_POINT.getName(), DEFAULTCOLUMN).getStringValue());

        geo = new GeoLocation();
        
        ColumnList<String> schdulersStatus = baseDao.readWithKey(ColumnFamily.CONFIGSETTINGS.getColumnFamily(), "schdulers~status");
        for(int i = 0 ; i < schdulersStatus.size() ; i++) {
        	cache.put(schdulersStatus.getColumnByIndex(i).getName(), schdulersStatus.getColumnByIndex(i).getStringValue());
        }
        pushingEvents = baseDao.readWithKey(ColumnFamily.CONFIGSETTINGS.getColumnFamily(), "default~key").getColumnNames();
        statMetrics = baseDao.readWithKey(ColumnFamily.CONFIGSETTINGS.getColumnFamily(), "stat~metrics");
        statKeys = statMetrics.getColumnNames();
        
        Rows<String, String> licenseRows = baseDao.readAllRows(ColumnFamily.LICENSE.getColumnFamily());
        licenseCache = new LinkedHashMap<String, Object>();
        for (Row<String, String> row : licenseRows) {
        	licenseCache.put(row.getKey(), row.getColumns().getLongValue("id", null));
		}
        Rows<String, String> resourceTypesRows = baseDao.readAllRows(ColumnFamily.RESOURCETYPES.getColumnFamily());
        resourceTypesCache = new LinkedHashMap<String, Object>();
        for (Row<String, String> row : resourceTypesRows) {
        	resourceTypesCache.put(row.getKey(), row.getColumns().getLongValue("id", null));
		}
        Rows<String, String> categoryRows = baseDao.readAllRows(ColumnFamily.CATEGORY.getColumnFamily());
        categoryCache = new LinkedHashMap<String, Object>();
        for (Row<String, String> row : categoryRows) {
        	categoryCache.put(row.getKey(), row.getColumns().getLongValue("id", null));
		}
        
    }

    public void clearCache(){
    	cache.clear();
    	Rows<String, String> operators = baseDao.readAllRows(ColumnFamily.REALTIMECONFIG.getColumnFamily());
        cache = new LinkedHashMap<String, String>();
        for (Row<String, String> row : operators) {
        	cache.put(row.getKey(), row.getColumns().getStringValue("aggregator_json", null));
		}
        cache.put(VIEWEVENTS, baseDao.readWithKeyColumn(ColumnFamily.CONFIGSETTINGS.getColumnFamily(), "views~events", DEFAULTCOLUMN).getStringValue());
        cache.put(ATMOSPHERENDPOINT, baseDao.readWithKeyColumn(ColumnFamily.CONFIGSETTINGS.getColumnFamily(), "atmosphere.end.point", DEFAULTCOLUMN).getStringValue());
        cache.put(VIEWUPDATEENDPOINT, baseDao.readWithKeyColumn(ColumnFamily.CONFIGSETTINGS.getColumnFamily(), LoaderConstants.VIEW_COUNT_REST_API_END_POINT.getName(), DEFAULTCOLUMN).getStringValue());
        pushingEvents = baseDao.readWithKey(ColumnFamily.CONFIGSETTINGS.getColumnFamily(), "default~key").getColumnNames();
        statMetrics = baseDao.readWithKey(ColumnFamily.CONFIGSETTINGS.getColumnFamily(), "stat~metrics");
        statKeys = statMetrics.getColumnNames();
        liveDashBoardDAOImpl.clearCache();
        ColumnList<String> schdulersStatus = baseDao.readWithKey(ColumnFamily.CONFIGSETTINGS.getColumnFamily(), "schdulers~status");
        for(int i = 0 ; i < schdulersStatus.size() ; i++) {
        	cache.put(schdulersStatus.getColumnByIndex(i).getName(), schdulersStatus.getColumnByIndex(i).getStringValue());
        }
        
        Rows<String, String> licenseRows = baseDao.readAllRows(ColumnFamily.LICENSE.getColumnFamily());
        licenseCache = new LinkedHashMap<String, Object>();
        for (Row<String, String> row : licenseRows) {
        	licenseCache.put(row.getKey(), row.getColumns().getLongValue("id", null));
		}
        Rows<String, String> resourceTypesRows = baseDao.readAllRows(ColumnFamily.RESOURCETYPES.getColumnFamily());
        resourceTypesCache = new LinkedHashMap<String, Object>();
        for (Row<String, String> row : resourceTypesRows) {
        	resourceTypesCache.put(row.getKey(), row.getColumns().getLongValue("id", null));
		}
        Rows<String, String> categoryRows = baseDao.readAllRows(ColumnFamily.CATEGORY.getColumnFamily());
        categoryCache = new LinkedHashMap<String, Object>();
        for (Row<String, String> row : categoryRows) {
        	categoryCache.put(row.getKey(), row.getColumns().getLongValue("id", null));
		}
    }
    
    /**
     * 
     * @param fields
     * @param startTime
     * @param userAgent
     * @param userIp
     * @param endTime
     * @param apiKey
     * @param eventName
     * @param gooruOId
     * @param contentId
     * @param query
     * @param gooruUId
     * @param userId
     * @param gooruId
     * @param type
     * @param parentEventId
     * @param context
     * @param reactionType
     * @param organizationUid
     * @param timeSpentInMs
     * @param answerId
     * @param attemptStatus
     * @param trySequence
     * @param requestMethod
     * @param eventId
     * 
     * Generate EventData Object 
     */
    public void handleLogMessage(String fields, Long startTime,
            String userAgent, String userIp, Long endTime, String apiKey,
            String eventName, String gooruOId, String contentId, String query,String gooruUId,String userId,String gooruId,String type,
            String parentEventId,String context,String reactionType,String organizationUid,Long timeSpentInMs,int[] answerId,int[] attemptStatus,int[] trySequence,String requestMethod, String eventId) {
    	EventData eventData = new EventData();
    	eventData.setEventId(eventId);
        eventData.setStartTime(startTime);
        eventData.setEndTime(endTime);
        eventData.setUserAgent(userAgent);
        eventData.setEventName(eventName);
        eventData.setUserIp(userIp);
        eventData.setApiKey(apiKey);
        eventData.setFields(fields);
        eventData.setGooruOId(gooruOId);
        eventData.setContentId(contentId);
        eventData.setQuery(query);
        eventData.setGooruUId(gooruUId);
        eventData.setUserId(userId);
        eventData.setGooruId(gooruId);
        eventData.setOrganizationUid(organizationUid);
        eventData.setType(type);
        eventData.setContext(context);
        eventData.setParentEventId(parentEventId);
        eventData.setTimeSpentInMs(timeSpentInMs);
        eventData.setAnswerId(answerId);
        eventData.setAttemptStatus(attemptStatus);
        eventData.setAttemptTrySequence(trySequence);
        eventData.setRequestMethod(requestMethod);
        handleLogMessage(eventData);
    }

    /**
     * 
     * @param eventData 
     * 		process EventData Object 
     * @exception ConnectionException
     * 		If the host is unavailable
     * 
     */
    public void handleLogMessage(EventData eventData) {
    	
    	// Increment Resource view counts for real time
    	
    	this.getAndSetAnswerStatus(eventData);
    	    	
    	if(eventData.getEventName().equalsIgnoreCase(LoaderConstants.CR.getName())){
    		eventData.setQuery(eventData.getReactionType());    		
    	}
    	
        if (StringUtils.isEmpty(eventData.getFields()) || eventData.getStartTime() == null) {
            return;
        }
        if (StringUtils.isEmpty(eventData.getEventType()) && !StringUtils.isEmpty(eventData.getType())) {
            eventData.setEventType(eventData.getType());
        }
        
        try {
	         ColumnList<String> existingRecord = null;
	         Long startTimeVal = null;
	         Long endTimeVal = null;

	         if (eventData.getEventId() != null) {
	        	 existingRecord = baseDao.readWithKey(ColumnFamily.EVENTDETAIL.getColumnFamily(),eventData.getEventId());
	        	 if (existingRecord != null && !existingRecord.isEmpty()) {
			         if ("start".equalsIgnoreCase(eventData.getEventType())) {
			        	 startTimeVal = existingRecord.getLongValue("start_time", null);
			         }
			         if ("stop".equalsIgnoreCase(eventData.getEventType())) {
			        	 endTimeVal = existingRecord.getLongValue("end_time", null);
			         }
			         if (startTimeVal == null && endTimeVal == null) {
			         	// This is a duplicate event. Don't do anything!
			         	return;
			         }
			      }
	         }
	         Map<String,Object> records = new HashMap<String, Object>();
	         records.put("event_name", eventData.getEventName());
	         records.put("api_key",eventData.getApiKey() != null ? eventData.getApiKey() : DEFAULT_API_KEY );
	         Collection<String> existingEventRecord = baseDao.getKey(ColumnFamily.DIMEVENTS.getColumnFamily(),records);
	
	         if(existingEventRecord == null && existingEventRecord.isEmpty()){
	        	 logger.info("Please add new event in to events table ");
	        	 return;
	         }
         
	         updateEventCompletion(eventData);
	
	         String eventKeyUUID = updateEvent(eventData);
	        if (eventKeyUUID == null) {
	            return;
	        }
	        /**
			 * write the JSON to Log file using kafka log writer module in aysnc
			 * mode. This will store/write all data to activity log file in log/event_api_logs/activity.log
			 */
			if (eventData.getFields() != null) {
				baseDao.saveEvent(ColumnFamily.EVENTDETAIL.getColumnFamily(),eventData);
				kafkaLogWriter.sendEventLog(eventData.getFields());
				logger.info("CORE: Writing to activity log - :"+ eventData.getFields().toString());
			}
	    
	
	        // Insert into event_timeline column family
	        Date eventDateTime = new Date(eventData.getStartTime());
	        String eventRowKey = minuteDateFormatter.format(eventDateTime).toString();
	        if(eventData.getEventType() == null || !eventData.getEventType().equalsIgnoreCase("completed-event")){
		        eventData.setEventKeyUUID(eventKeyUUID.toString());
		        String duplicatekey = eventRowKey+"~"+eventRowKey;
		        baseDao.updateTimeline(ColumnFamily.EVENTTIMELINE.getColumnFamily(), eventData, eventRowKey);
	        }
	        try {
				updateActivityStream(eventData.getEventId());
			} catch (JSONException e) {
				logger.info("Json Exception while saving Activity Stream via old event format {}", e);
			}
        } catch (ConnectionException e) {
        	logger.info("Exception while processing update for rowkey {} ", e);
       }
    }

    public void handleEventObjectMessage(EventObject eventObject) throws JSONException, ConnectionException, IOException, GeoIp2Exception{

    	Map<String,String> eventMap = new LinkedHashMap<String, String>();
    	String aggregatorJson = cache.get(eventMap.get("eventName"));
    	
    	try {
	    	eventMap = JSONDeserializer.deserializeEventObject(eventObject);    	

	    	if (eventObject.getFields() != null) {
				logger.info("CORE: Writing to activity log - :"+ eventObject.getFields().toString());
				kafkaLogWriter.sendEventLog(eventObject.getFields());
				//Save Activity in ElasticSearch
				this.saveActivityInIndex(eventObject.getFields());
				
			}

	    	eventMap = this.formatEventMap(eventObject, eventMap);
	    	
	    	String apiKey = eventObject.getApiKey() != null ? eventObject.getApiKey() : DEFAULT_API_KEY;
	    	
	    	Map<String,Object> records = new HashMap<String, Object>();
	    	records.put("event_name", eventMap.get("eventName"));
	    	records.put("api_key",apiKey);
	    	Collection<String> eventId = baseDao.getKey(ColumnFamily.DIMEVENTS.getColumnFamily(),records);

	    	if(eventId == null || eventId.isEmpty()){
	    		UUID uuid = TimeUUIDUtils.getUniqueTimeUUIDinMillis();
	    		records.put("event_id", uuid.toString());
	    		String key = apiKey +SEPERATOR+uuid.toString();
				 baseDao.saveBulkList(ColumnFamily.DIMEVENTS.getColumnFamily(),key,records);
			 }		
	    	
	    	updateEventObjectCompletion(eventObject);
	    	
			String eventKeyUUID = baseDao.saveEventObject(ColumnFamily.EVENTDETAIL.getColumnFamily(),null,eventObject);
			 
			if (eventKeyUUID == null) {
			    return;
			}
			
			Date eventDateTime = new Date(eventObject.getStartTime());
			String eventRowKey = minuteDateFormatter.format(eventDateTime).toString();
	
			if(eventObject.getEventType() == null || !eventObject.getEventType().equalsIgnoreCase("stop") || !eventObject.getEventType().equalsIgnoreCase("completed-event")){
			    baseDao.updateTimelineObject(ColumnFamily.EVENTTIMELINE.getColumnFamily(), eventRowKey,eventKeyUUID.toString(),eventObject);
			}
						
			logger.info("From cachee : {} ", cache.get(ATMOSPHERENDPOINT));
			
			if(aggregatorJson != null && !aggregatorJson.isEmpty() && !aggregatorJson.equalsIgnoreCase(RAWUPDATE)){		 	

				liveAggregator.realTimeMetrics(eventMap, aggregatorJson);	
			}
		  
			if(aggregatorJson != null && !aggregatorJson.isEmpty() && aggregatorJson.equalsIgnoreCase(RAWUPDATE)){
				liveAggregator.updateRawData(eventMap);
			}
			
			liveDashBoardDAOImpl.callCountersV2(eventMap);
			
		
    	}catch(Exception e){
    		kafkaLogWriter.sendErrorEventLog(eventObject.getFields());
			logger.info("Writing error log : {} ",eventObject.getEventId());
    	}

    	try {
    		if(aggregatorJson != null && !aggregatorJson.isEmpty() && !aggregatorJson.equalsIgnoreCase(RAWUPDATE)){		 	
    			
				liveAggregator.realTimeMetrics(eventMap, aggregatorJson);
	
				microAggregator.sendEventForAggregation(eventObject.getFields());			
			}

			if(cache.get(VIEWEVENTS).contains(eventMap.get("eventName"))){
				liveDashBoardDAOImpl.addContentForPostViews(eventMap);
			}
			
			liveDashBoardDAOImpl.findDifferenceInCount(eventMap);
	
			liveDashBoardDAOImpl.addApplicationSession(eventMap);
	
			liveDashBoardDAOImpl.saveGeoLocations(eventMap);
			
			
			/*
			 * To be Re-enable 
			 * 

			if(pushingEvents.contains(eventMap.get("eventName"))){
				liveDashBoardDAOImpl.pushEventForAtmosphere(cache.get(ATMOSPHERENDPOINT),eventMap);
			}
	
			if(eventMap.get("eventName").equalsIgnoreCase(LoaderConstants.CRPV1.getName())){
				liveDashBoardDAOImpl.pushEventForAtmosphereProgress(atmosphereEndPoint, eventMap);
			}
			
			*/
	
    	}catch(Exception e){
    		logger.info("Exception in handleEventObjectHandler Post Process : {} ",e);
    	}
   }
    /**
     * 
     * @param eventData
     * 		Update the event is completion status 
     * @throws ConnectionException
     * 		If the host is unavailable
     */
    
    private void updateEventObjectCompletion(EventObject eventObject) throws ConnectionException {

    	Long endTime = eventObject.getEndTime(), startTime = eventObject.getStartTime();
        long timeInMillisecs = 0L;
        if (endTime != null && startTime != null) {
            timeInMillisecs = endTime - startTime;
        }
        boolean eventComplete = false;

        eventObject.setTimeInMillSec(timeInMillisecs);

        if (StringUtils.isEmpty(eventObject.getEventId())) {
            return;
        }

			ColumnList<String> existingRecord = baseDao.readWithKey(ColumnFamily.EVENTDETAIL.getColumnFamily(),eventObject.getEventId());
			if (existingRecord != null && !existingRecord.isEmpty()) {
			    if ("stop".equalsIgnoreCase(eventObject.getEventType())) {
			        startTime = existingRecord.getLongValue("start_time", null);
			        //Update startTime with existingRecord, IF existingRecord.startTime < startTime
			    } else {
			        endTime = existingRecord.getLongValue("end_time", null);
			        // Update endTime with existing record IF existingRecord.endTime > endTime
			    }
			    eventComplete = true;
			}
			// Time taken for the event in milliseconds derived from the start / stop events.
			if (endTime != null && startTime != null) {
				timeInMillisecs = endTime - startTime;
			}
			if (timeInMillisecs > 1147483647) {
			    // When time in Milliseconds is very very huge, set to min time to serve the call.
			    timeInMillisecs = 30;
			    // Since this is an error condition, log it.
			}

			eventObject.setStartTime(startTime);
			eventObject.setEndTime(endTime);

        if (eventComplete) {
        	eventObject.setTimeInMillSec(timeInMillisecs);
            eventObject.setEventType("completed-event");
            eventObject.setEndTime(endTime);
            eventObject.setStartTime(startTime);
        }

        if(!StringUtils.isEmpty(eventObject.getParentEventId())){
        	ColumnList<String> existingParentRecord = baseDao.readWithKey(ColumnFamily.EVENTDETAIL.getColumnFamily(),eventObject.getParentEventId());
        	if (existingParentRecord != null && !existingParentRecord.isEmpty()) {
        		Long parentStartTime = existingParentRecord.getLongValue("start_time", null);
        		baseDao.saveLongValue(ColumnFamily.EVENTDETAIL.getColumnFamily(), eventObject.getParentEventId(), "end_time", endTime);
        		baseDao.saveLongValue(ColumnFamily.EVENTDETAIL.getColumnFamily(), eventObject.getParentEventId(), "time_spent_in_millis", (endTime-parentStartTime));
        	}
        }

    }
    
    /**
     * 
     * @param startTime
     * @param endTime
     * @param customEventName
     * @throws ParseException
     */
    
    public void updateStaging(String startTime , String endTime,String customEventName,String apiKey) throws ParseException {
    	SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyyMMddkkmm");
    	SimpleDateFormat dateIdFormatter = new SimpleDateFormat("yyyy-MM-dd 00:00:00+0000");
    	Calendar cal = Calendar.getInstance();
    	
    	String dateId = null;
    	Long weekId = null;
    	Long monthId = null;
    	String eventId = null;
    	Long yearId = null;
    	String processingDate = null;
    	
    	//Get all the event name and store for Caching
    	Map<String,String> events = new LinkedHashMap<String, String>();
    	
    	Rows<String, String> eventRows  = baseDao.readAllRows(ColumnFamily.DIMEVENTS.getColumnFamily());
    	
    	for(Row<String, String> eventRow : eventRows){
    		ColumnList<String> eventColumns = eventRow.getColumns();
    		events.put(eventColumns.getStringValue("event_name", null), eventColumns.getStringValue("event_id", null));
    	}
    	//Process records for every minute
    	for (Long startDate = Long.parseLong(startTime) ; startDate <= Long.parseLong(endTime);) {
    		String currentDate = dateIdFormatter.format(dateFormatter.parse(startDate.toString()));
    		
    		logger.info("Porcessing Date : {}" , startDate.toString());
    		
   		 	if(!currentDate.equalsIgnoreCase(processingDate)){
   		 			processingDate = currentDate;
   		 		Rows<String, String> dateDetail = baseDao.readIndexedColumn(ColumnFamily.DIMDATE.getColumnFamily(),"date",currentDate);
   		 			
   		 		for(Row<String, String> dateIds : dateDetail){
   		 			ColumnList<String> columns = dateIds.getColumns();
   		 			dateId = dateIds.getKey().toString();
   		 			monthId = columns.getLongValue("month_date_id", 0L);
   		 			weekId = columns.getLongValue("week_date_id", 0L);
   		 			yearId = columns.getLongValue("year_date_id", 0L);
   		 		}	
   		 	}
   		 	
   		 	//Retry 100 times to get Date ID if Cassandra failed to respond
   		 	int dateTrySeq = 1;
   		 	while((dateId == null || dateId.equalsIgnoreCase("0")) && dateTrySeq < 100){
   		 	
   		 		Rows<String, String> dateDetail = baseDao.readIndexedColumn(ColumnFamily.DIMDATE.getColumnFamily(),"date",currentDate);
	 			
		 		for(Row<String, String> dateIds : dateDetail){
		 			ColumnList<String> columns = dateIds.getColumns();
   		 			dateId = dateIds.getKey().toString();
   		 			monthId = columns.getLongValue("month_date_id", 0L);
   		 			weekId = columns.getLongValue("week_date_id", 0L);
   		 			yearId = columns.getLongValue("year_date_id", 0L);
		 		}
   		 		dateTrySeq++;
   		 	}
   		 	
   		 	//Generate Key if loads custom Event Name
   		 	String timeLineKey = null;   		 	
   		 	if(customEventName == null || customEventName  == "") {
   		 		timeLineKey = startDate.toString();
   		 	} else {
   		 		timeLineKey = startDate.toString()+"~"+customEventName;
   		 	}
   		 	
   		 	//Read Event Time Line for event keys and create as a Collection
   		 ColumnList<String> eventUUID = baseDao.readWithKey(ColumnFamily.EVENTTIMELINE.getColumnFamily(), timeLineKey);
	    	if(eventUUID == null && eventUUID.isEmpty() ) {
	    		logger.info("No events in given timeline :  {}",startDate);
	    		return;
	    	}
	 
	    	Collection<String> eventDetailkeys = new ArrayList<String>();
	    	for(int i = 0 ; i < eventUUID.size() ; i++) {
	    		String eventDetailUUID = eventUUID.getColumnByIndex(i).getStringValue();
	    		eventDetailkeys.add(eventDetailUUID);
	    	}
	    	
	    	//Read all records from Event Detail
	    	Rows<String, String> eventDetailsNew = baseDao.readWithKeyList(ColumnFamily.EVENTDETAIL.getColumnFamily(), eventDetailkeys);

	    	for (Row<String, String> row : eventDetailsNew) {
	    		row.getColumns().getStringValue("event_name", null);
	    		String searchType = row.getColumns().getStringValue("event_name", null);
	    		
	    		//Skip Invalid Events
	    		if(searchType == null ) {
	    			continue;
	    		}
	    		
	    		if(searchType.equalsIgnoreCase("session-expired")) {
	    			continue;
	    		}
	    		
	    		//Get Event ID for corresponding Event Name
	    		 eventId = events.get(searchType);
	    		 
	    		
	    		if(eventId == null) {
	    			continue;
	    		}
		    	
	    		String fields = row.getColumns().getStringValue("fields", null);
	    		if(fields != null){
	    		try {
	    			JSONObject jsonField = new JSONObject(fields);
		    			if(jsonField.has("version")){
		    				EventObject eventObjects = new Gson().fromJson(fields, EventObject.class);
		    				Map<String, Object> eventMap = JSONDeserializer.deserializeEventObject(eventObjects);    	
		    				
		    				eventMap.put("eventName", eventObjects.getEventName());
		    		    	eventMap.put("eventId", eventObjects.getEventId());
		    		    	eventMap.put("eventTime",String.valueOf(eventObjects.getStartTime()));
		    		    	if(eventMap.get(CONTENTGOORUOID) != null){		    		    		
		    		    		eventMap =  this.getTaxonomyInfo(eventMap, String.valueOf(eventMap.get(CONTENTGOORUOID)));
		    		    		eventMap =  this.getContentInfo(eventMap, String.valueOf(eventMap.get(CONTENTGOORUOID)));
		    		    	}
		    		    	if(eventMap.get(GOORUID) != null){  
		    		    		eventMap =   this.getUserInfo(eventMap,String.valueOf(eventMap.get(GOORUID)));
		    		    	}
		    		    	eventMap.put("dateId", dateId);
		    		    	eventMap.put("weekId", weekId);
		    		    	eventMap.put("monthId", monthId);
		    		    	eventMap.put("yearId", yearId);
		    		    	
		    		    	liveDashBoardDAOImpl.saveInStaging(eventMap);
		    			} 
		    			else{
		    				   Iterator<?> keys = jsonField.keys();
		    				   Map<String,Object> eventMap = new HashMap<String, Object>();
		    				   while( keys.hasNext() ){
		    			            String key = (String)keys.next();
		    			            if(key.equalsIgnoreCase("contentGooruId") || key.equalsIgnoreCase("gooruOId") || key.equalsIgnoreCase("gooruOid")){
		    			            	eventMap.put(CONTENTGOORUOID, String.valueOf(jsonField.get(key)));
		    			            }

		    			            if(key.equalsIgnoreCase("gooruUId") || key.equalsIgnoreCase("gooruUid")){
		    			            	eventMap.put(GOORUID, String.valueOf(jsonField.get(key)));
		    			            }
		    			            eventMap.put(key,String.valueOf(jsonField.get(key)));
		    			        }
		    				   if(eventMap.get(CONTENTGOORUOID) != null){
		    				   		eventMap =  this.getTaxonomyInfo(eventMap, String.valueOf(eventMap.get(CONTENTGOORUOID)));
		    				   		eventMap =  this.getContentInfo(eventMap, String.valueOf(eventMap.get(CONTENTGOORUOID)));
		    				   }
		    				   if(eventMap.get(GOORUID) != null){
		    					   eventMap =   this.getUserInfo(eventMap,String.valueOf(eventMap.get(GOORUID)));
		    				   }
		    				   	eventMap.put("dateId", dateId);
			    		    	eventMap.put("weekId", weekId);
			    		    	eventMap.put("monthId", monthId);
			    		    	eventMap.put("yearId", yearId);	
			    	    		liveDashBoardDAOImpl.saveInStaging(eventMap);
		    		     }
					} catch (Exception e) {
						logger.info("Error while Migration : {} ",e);
					}
	    			}	    		
	    		}
	    	//Incrementing time - one minute
	    	cal.setTime(dateFormatter.parse(""+startDate));
	    	cal.add(Calendar.MINUTE, 1);
	    	Date incrementedTime =cal.getTime(); 
	    	startDate = Long.parseLong(dateFormatter.format(incrementedTime));
    	}
    	

    	logger.info("Process Ends  : Inserted successfully");
    }

    
    public void updateStagingES(String startTime , String endTime,String customEventName,String apiKey) throws ParseException {
    	SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyyMMddkkmm");
    	SimpleDateFormat dateIdFormatter = new SimpleDateFormat("yyyy-MM-dd 00:00:00+0000");
    	Calendar cal = Calendar.getInstance();
    	for (Long startDate = Long.parseLong(startTime) ; startDate <= Long.parseLong(endTime);) {
    		String currentDate = dateIdFormatter.format(dateFormatter.parse(startDate.toString()));
    		int currentHour = dateFormatter.parse(startDate.toString()).getHours();
    		int currentMinute = dateFormatter.parse(startDate.toString()).getMinutes();
    		
    		logger.info("Porcessing Date : {}" , startDate.toString());
   		 	String timeLineKey = null;   		 	
   		 	if(customEventName == null || customEventName  == "") {
   		 		timeLineKey = startDate.toString();
   		 	} else {
   		 		timeLineKey = startDate.toString()+"~"+customEventName;
   		 	}
   		 	
   		 	//Read Event Time Line for event keys and create as a Collection
   		 	ColumnList<String> eventUUID = baseDao.readWithKey(ColumnFamily.EVENTTIMELINE.getColumnFamily(), timeLineKey,null);
   		 	
	    	if(eventUUID != null &&  !eventUUID.isEmpty() ) {

		    	Collection<String> eventDetailkeys = new ArrayList<String>();
		    	for(int i = 0 ; i < eventUUID.size() ; i++) {
		    		String eventDetailUUID = eventUUID.getColumnByIndex(i).getStringValue();
		    		logger.info("eventDetailUUID  : " + eventDetailUUID);
		    		eventDetailkeys.add(eventDetailUUID);
		    	}
		    	
		    	//Read all records from Event Detail
		    	Rows<String, String> eventDetailsNew = baseDao.readWithKeyList(ColumnFamily.EVENTDETAIL.getColumnFamily(), eventDetailkeys,null);
		    	
		    	for (Row<String, String> row : eventDetailsNew) {
		    		logger.info("Fields : " + row.getColumns().getStringValue("fields", null));
		    		this.saveActivityInIndex(row.getColumns().getStringValue("fields", null));
		    	}
	    	}
	    	//Incrementing time - one minute
	    	cal.setTime(dateFormatter.parse(""+startDate));
	    	cal.add(Calendar.MINUTE, 1);
	    	Date incrementedTime =cal.getTime(); 
	    	startDate = Long.parseLong(dateFormatter.format(incrementedTime));
	    }
	    
    }
    
    public void pathWayMigration(String startTime , String endTime,String customEventName) throws ParseException {
    	SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyyMMddkkmm");
    	SimpleDateFormat dateIdFormatter = new SimpleDateFormat("yyyy-MM-dd 00:00:00+0000");
    	Calendar cal = Calendar.getInstance();
    	for (Long startDate = Long.parseLong(startTime) ; startDate <= Long.parseLong(endTime);) {
    		String currentDate = dateIdFormatter.format(dateFormatter.parse(startDate.toString()));
    		int currentHour = dateFormatter.parse(startDate.toString()).getHours();
    		int currentMinute = dateFormatter.parse(startDate.toString()).getMinutes();
    		
    		logger.info("Porcessing Date : {}" , startDate.toString());
   		 	String timeLineKey = null;   		 	
   		 	if(customEventName == null || customEventName  == "") {
   		 		timeLineKey = startDate.toString();
   		 	} else {
   		 		timeLineKey = startDate.toString()+"~"+customEventName;
   		 	}
   		 	
   		 	//Read Event Time Line for event keys and create as a Collection
   		 	ColumnList<String> eventUUID = baseDao.readWithKey(ColumnFamily.EVENTTIMELINE.getColumnFamily(), timeLineKey);
   		 	
	    	if(eventUUID != null &&  !eventUUID.isEmpty() ) {

		    	Collection<String> eventDetailkeys = new ArrayList<String>();
		    	for(int i = 0 ; i < eventUUID.size() ; i++) {
		    		String eventDetailUUID = eventUUID.getColumnByIndex(i).getStringValue();
		    		logger.info("eventDetailUUID  : " + eventDetailUUID);
		    		eventDetailkeys.add(eventDetailUUID);
		    	}
		    	
		    	//Read all records from Event Detail
		    	Rows<String, String> eventDetailsNew = baseDao.readWithKeyList(ColumnFamily.EVENTDETAIL.getColumnFamily(), eventDetailkeys);
		    	
		    	for (Row<String, String> row : eventDetailsNew) {
		    		String fields = row.getColumns().getStringValue("fields", null);
		    		
		    		try {

		    		JSONObject jsonField = new JSONObject(fields);
	    		
		    		if(jsonField.has("version")){
		    		
	    				EventObject eventObjects = new Gson().fromJson(fields, EventObject.class);
		    		
	    				Map<String,String> eventMap = JSONDeserializer.deserializeEventObject(eventObjects); 
	    				
						eventMap = this.formatEventMap(eventObjects, eventMap);
						
						String aggregatorJson = cache.get(eventMap.get("eventName"));
						
							if(aggregatorJson != null && !aggregatorJson.isEmpty() && !aggregatorJson.equalsIgnoreCase(RAWUPDATE)){
								logger.info("Fields : " + fields);
								logger.info("SessionId : " + eventMap.get(SESSION));

								liveAggregator.realTimeMetricsMigration(eventMap, aggregatorJson);
							}
	    				}
					} catch (Exception e) {
						logger.info("Exception : " + e);
					} 
		    		
		    		}
		    	
	    	}
	    	//Incrementing time - one minute
	    	cal.setTime(dateFormatter.parse(""+startDate));
	    	cal.add(Calendar.MINUTE, 1);
	    	Date incrementedTime =cal.getTime(); 
	    	startDate = Long.parseLong(dateFormatter.format(incrementedTime));
	    }
	    
    }
    
    public void migrateEventsToCounter(String startTime , String endTime,String customEventName) throws ParseException {
    	logger.info("counter job started");
    	SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyyMMddkkmm");
    	SimpleDateFormat dateIdFormatter = new SimpleDateFormat("yyyy-MM-dd 00:00:00+0000");
    	Calendar cal = Calendar.getInstance();
    	for (Long startDate = Long.parseLong(startTime) ; startDate <= Long.parseLong(endTime);) {
    		String currentDate = dateIdFormatter.format(dateFormatter.parse(startDate.toString()));
    		int currentHour = dateFormatter.parse(startDate.toString()).getHours();
    		int currentMinute = dateFormatter.parse(startDate.toString()).getMinutes();
    		
    		logger.info("Porcessing Date : {}" , startDate.toString());
   		 	String timeLineKey = null;   		 	
   		 	if(customEventName == null || customEventName  == "") {
   		 		timeLineKey = startDate.toString();
   		 	} else {
   		 		timeLineKey = startDate.toString()+"~"+customEventName;
   		 	}
   		 	
   		 	//Read Event Time Line for event keys and create as a Collection
   		 	ColumnList<String> eventUUID = baseDao.readWithKey(ColumnFamily.EVENTTIMELINE.getColumnFamily(), timeLineKey);
   		 	
	    	if(eventUUID != null &&  !eventUUID.isEmpty() ) {

		    	Collection<String> eventDetailkeys = new ArrayList<String>();
		    	for(int i = 0 ; i < eventUUID.size() ; i++) {
		    		String eventDetailUUID = eventUUID.getColumnByIndex(i).getStringValue();
		    		logger.info("eventDetailUUID  : " + eventDetailUUID);
		    		eventDetailkeys.add(eventDetailUUID);
		    	}
		    	
		    	//Read all records from Event Detail
		    	Rows<String, String> eventDetailsNew = baseDao.readWithKeyList(ColumnFamily.EVENTDETAIL.getColumnFamily(), eventDetailkeys);
		    	
		    	for (Row<String, String> row : eventDetailsNew) {
		    		logger.info("Fields : " + row.getColumns().getStringValue("fields", null));
		    		String fields = row.getColumns().getStringValue("fields", null);
		        	if(fields != null){
		    			try {
		    				JSONObject jsonField = new JSONObject(fields);
		    	    			if(jsonField.has("version")){
		    	    				EventObject eventObjects = new Gson().fromJson(fields, EventObject.class);
		    	    				
		    	    				Map<String,String> eventMap = JSONDeserializer.deserializeEventObject(eventObjects);    	
		    	    				eventMap.put("eventName", eventObjects.getEventName());
		    	    		    	eventMap.put("startTime",String.valueOf(eventObjects.getStartTime()));		    	    		    	
		    	    		    	eventMap.put("eventId", eventObjects.getEventId());
		    	    	    		
		    	    	    		liveDashBoardDAOImpl.callCountersV2Custom(eventMap);
		    	    			} 
		    	    			else{
		    	    				   Iterator<?> keys = jsonField.keys();
		    	    				   Map<String,String> eventMap = new HashMap<String, String>();
		    	    				   while( keys.hasNext() ){
		    	    			            String key = (String)keys.next();
		    	    			            if(key.equalsIgnoreCase("contentGooruId") || key.equalsIgnoreCase("gooruOId") || key.equalsIgnoreCase("gooruOid")){
		    	    			            	eventMap.put(CONTENTGOORUOID, String.valueOf(jsonField.get(key)));
		    	    			            }
		    	
		    	    			            if(key.equalsIgnoreCase("gooruUId") || key.equalsIgnoreCase("gooruUid")){
		    	    			            	eventMap.put(GOORUID, String.valueOf(jsonField.get(key)));
		    	    			            }
		    	    			            eventMap.put(key,String.valueOf(jsonField.get(key)));
		    	    			        }
		    		    	    		
		    	    				   liveDashBoardDAOImpl.callCountersV2Custom(eventMap);
		    	    		     }
		    				} catch (Exception e) {
		    					logger.info("Error while Migration : {} ",e);
		    				}
		    				}
		    		
		        
		    	}
	    	}
	    	//Incrementing time - one minute
	    	cal.setTime(dateFormatter.parse(""+startDate));
	    	cal.add(Calendar.MINUTE, 1);
	    	Date incrementedTime =cal.getTime(); 
	    	startDate = Long.parseLong(dateFormatter.format(incrementedTime));
	    }
	    
    }
    @Async
    public void saveActivityInIndex(String fields){
    	if(fields != null){
			try {
				JSONObject jsonField = new JSONObject(fields);
	    			if(jsonField.has("version")){
	    				EventObject eventObjects = new Gson().fromJson(fields, EventObject.class);
	    				Map<String,Object> eventMap = JSONDeserializer.deserializeEventObject(eventObjects);    	
	    				
	    				eventMap.put("eventName", eventObjects.getEventName());
	    		    	eventMap.put("eventId", eventObjects.getEventId());
	    		    	eventMap.put("eventTime",String.valueOf(eventObjects.getStartTime()));
	    		    	if(eventMap.get(CONTENTGOORUOID) != null){		    		    		
	    		    		eventMap =  this.getTaxonomyInfo(eventMap, String.valueOf(eventMap.get(CONTENTGOORUOID)));
	    		    		eventMap =  this.getContentInfo(eventMap, String.valueOf(eventMap.get(CONTENTGOORUOID)));
	    		    	}
	    		    	if(eventMap.get(GOORUID) != null){  
	    		    		eventMap =   this.getUserInfo(eventMap,String.valueOf(eventMap.get(GOORUID)));
	    		    	}
	    	    		
	    	    		liveDashBoardDAOImpl.saveActivityInESIndex(eventMap,ESIndexices.EVENTLOGGERINFO.getIndex(), IndexType.EVENTDETAIL.getIndexType(), String.valueOf(eventMap.get("eventId")));
	    			} 
	    			else{
	    				   Iterator<?> keys = jsonField.keys();
	    				   Map<String,Object> eventMap = new HashMap<String, Object>();
	    				   while( keys.hasNext() ){
	    			            String key = (String)keys.next();
	    			            if(key.equalsIgnoreCase("contentGooruId") || key.equalsIgnoreCase("gooruOId") || key.equalsIgnoreCase("gooruOid")){
	    			            	eventMap.put(CONTENTGOORUOID, String.valueOf(jsonField.get(key)));
	    			            }
	
	    			            if(key.equalsIgnoreCase("gooruUId") || key.equalsIgnoreCase("gooruUid")){
	    			            	eventMap.put(GOORUID, String.valueOf(jsonField.get(key)));
	    			            }
	    			            eventMap.put(key,String.valueOf(jsonField.get(key)));
	    			        }
	    				   if(eventMap.get(CONTENTGOORUOID) != null){
	    				   		eventMap =  this.getTaxonomyInfo(eventMap, String.valueOf(eventMap.get(CONTENTGOORUOID)));
	    				   		eventMap =  this.getContentInfo(eventMap, String.valueOf(eventMap.get(CONTENTGOORUOID)));
	    				   }
	    				   if(eventMap.get(GOORUID) != null ){
	    					   eventMap =   this.getUserInfo(eventMap,String.valueOf(eventMap.get(GOORUID)));
	    				   }
		    	    		
		    	    		liveDashBoardDAOImpl.saveActivityInESIndex(eventMap,ESIndexices.EVENTLOGGERINFO.getIndex(), IndexType.EVENTDETAIL.getIndexType(), String.valueOf(eventMap.get("eventId")));
	    		     }
				} catch (Exception e) {
					logger.info("Error while Migration : {} ",e);
				}
				}
		
    }
    
    public Map<String, Object> getUserInfo(Map<String,Object> eventMap , String gooruUId){
    	Collection<String> user = new ArrayList<String>();
    	user.add(gooruUId);
    	Rows<String, String> eventDetailsNew = baseDao.readWithKeyList(ColumnFamily.EXTRACTEDUSER.getColumnFamily(), user);
    	for (Row<String, String> row : eventDetailsNew) {
    		ColumnList<String> userInfo = row.getColumns();
    		for(int i = 0 ; i < userInfo.size() ; i++) {
    			String columnName = userInfo.getColumnByIndex(i).getName();
    			String value = userInfo.getColumnByIndex(i).getStringValue();
    			if(!columnName.equalsIgnoreCase("teacher") && !columnName.equalsIgnoreCase("organizationUId")){    				
    				eventMap.put(columnName, Long.valueOf(value));
    			}
    			if(value != null && columnName.equalsIgnoreCase("organizationUId")){
    				eventMap.put(columnName, value);
    			}
    			if(value != null && columnName.equalsIgnoreCase("teacher")){
    				JSONArray jArray = new JSONArray();
    				for(String val : value.split(",")){
    					jArray.put(val);
    				}
    				eventMap.put(columnName, jArray.toString());
    			}
    		}
    	}
		return eventMap;
    }
    public Map<String,Object> getContentInfo(Map<String,Object> eventMap,String gooruOId){
    	ColumnList<String> resource = baseDao.readWithKey(ColumnFamily.DIMRESOURCE.getColumnFamily(), "GLP~"+gooruOId);
    		if(resource != null){
    			eventMap.put("title", resource.getStringValue("title", null));
    			eventMap.put("description",resource.getStringValue("description", null));
    			eventMap.put("sharing", resource.getStringValue("sharing", null));
    			eventMap.put("contentType", resource.getStringValue("category", null));
    			eventMap.put("license", resource.getStringValue("license_name", null));
    			
    			if(resource.getColumnByName("type_name") != null){
					if(resourceTypesCache.containsKey(resource.getColumnByName("type_name").getStringValue())){    							
						eventMap.put("resourceTypeId", resourceTypesCache.get(resource.getColumnByName("type_name").getStringValue()));
					}
				}
				if(resource.getColumnByName("category") != null){
					if(categoryCache.containsKey(resource.getColumnByName("category").getStringValue())){    							
						eventMap.put("resourceCategoryId", categoryCache.get(resource.getColumnByName("category").getStringValue()));
					}
				}
				ColumnList<String> questionCount = baseDao.readWithKey(ColumnFamily.QUESTIONCOUNT.getColumnFamily(), gooruOId);
				if(questionCount != null && !questionCount.isEmpty()){
					Long questionCounts = questionCount.getLongValue("questionCount", 0L);
					eventMap.put("questionCount", questionCounts);
					if(questionCounts > 0L){
						if(resourceTypesCache.containsKey(resource.getColumnByName("type_name").getStringValue())){    							
							eventMap.put("resourceTypeId", resourceTypesCache.get(resource.getColumnByName("type_name").getStringValue()));
						}	
					}
				}else{
					eventMap.put("questionCount",0L);
				}
    		} 
    	
		return eventMap;
    }
    public Map<String,Object> getTaxonomyInfo(Map<String,Object> eventMap,String gooruOid){
    	Collection<String> user = new ArrayList<String>();
    	user.add(gooruOid);
    	Map<String,String> whereColumn = new HashMap<String, String>();
    	whereColumn.put("gooru_oid", gooruOid);
    	Rows<String, String> eventDetailsNew = baseDao.readIndexedColumnList(ColumnFamily.DIMCONTENTCLASSIFICATION.getColumnFamily(), whereColumn);
    	Long subjectCode = 0L;
    	Long courseCode = 0L;
    	Long unitCode = 0L;
    	Long topicCode = 0L;
    	Long lessonCode = 0L;
    	Long conceptCode= 0L;
    	
    	JSONArray taxArray = new JSONArray();
    	for (Row<String, String> row : eventDetailsNew) {
    		ColumnList<String> userInfo = row.getColumns();
    			Long root = userInfo.getColumnByName("root_node_id") != null ? userInfo.getColumnByName("root_node_id").getLongValue() : 0L;
    			if(root == 20000L){
	    			Long value = userInfo.getColumnByName("code_id") != null ?userInfo.getColumnByName("code_id").getLongValue() : 0L;
	    			Long depth = userInfo.getColumnByName("depth") != null ?  userInfo.getColumnByName("depth").getLongValue() : 0L;
	    			if(value != null &&  depth == 1L){    				
	    				subjectCode = value;
	    			} 
	    			else if(value != null && depth == 2L){
	    			ColumnList<String> columns = baseDao.readWithKey(ColumnFamily.EXTRACTEDCODE.getColumnFamily(), String.valueOf(value));
	    			Long subject = columns.getColumnByName("subject_code_id") != null ? columns.getColumnByName("subject_code_id").getLongValue() : 0L;
	    				if(subjectCode == 0L)
	    				subjectCode = subject;
	    				courseCode = value;
	    			}
	    			
	    			else if(value != null && depth == 3L){
	    				ColumnList<String> columns = baseDao.readWithKey(ColumnFamily.EXTRACTEDCODE.getColumnFamily(), String.valueOf(value));
		    			Long subject = columns.getColumnByName("subject_code_id") != null ? columns.getColumnByName("subject_code_id").getLongValue() : 0L;
		    			Long course = columns.getColumnByName("course_code_id") != null ? columns.getColumnByName("course_code_id").getLongValue() : 0L;
		    			if(subjectCode == 0L)
		    			subjectCode = subject;
		    			if(courseCode == 0L)
		    			courseCode = course;
		    			unitCode = value;
	    			}
	    			else if(value != null && depth == 4L){
	    				ColumnList<String> columns = baseDao.readWithKey(ColumnFamily.EXTRACTEDCODE.getColumnFamily(), String.valueOf(value));
		    			Long subject = columns.getColumnByName("subject_code_id") != null ? columns.getColumnByName("subject_code_id").getLongValue() : 0L;
		    			Long course = columns.getColumnByName("course_code_id") != null ? columns.getColumnByName("course_code_id").getLongValue() : 0L;
		    			Long unit = columns.getColumnByName("unit_code_id") != null ? columns.getColumnByName("unit_code_id").getLongValue() : 0L;
		    			if(subjectCode == 0L)
			    		subjectCode = subject;
			    		if(courseCode == 0L)
			    		courseCode = course;
			    		if(unitCode == 0L)
			    		unitCode = unit;
			    		topicCode = value ;
	    			}
	    			else if(value != null && depth == 5L){
	    				ColumnList<String> columns = baseDao.readWithKey(ColumnFamily.EXTRACTEDCODE.getColumnFamily(), String.valueOf(value));
		    			Long subject = columns.getColumnByName("subject_code_id") != null ? columns.getColumnByName("subject_code_id").getLongValue() : 0L;
		    			Long course = columns.getColumnByName("course_code_id") != null ? columns.getColumnByName("course_code_id").getLongValue() : 0L;
		    			Long unit = columns.getColumnByName("unit_code_id") != null ? columns.getColumnByName("unit_code_id").getLongValue() : 0L;
		    			Long topic = columns.getColumnByName("topic_code_id") != null ? columns.getColumnByName("topic_code_id").getLongValue() : 0L;
		    				if(subjectCode == 0L)
				    		subjectCode = subject;
				    		if(courseCode == 0L)
				    		courseCode = course;
				    		if(unitCode == 0L)
				    		unitCode = unit;
				    		if(topicCode == 0L)
				    		topicCode = topic ;
				    		lessonCode = value;
	    			}
	    			else if(value != null && depth == 6L){
	    				ColumnList<String> columns = baseDao.readWithKey(ColumnFamily.EXTRACTEDCODE.getColumnFamily(), String.valueOf(value));
		    			Long subject = columns.getColumnByName("subject_code_id") != null ? columns.getColumnByName("subject_code_id").getLongValue() : 0L;
		    			Long course = columns.getColumnByName("course_code_id") != null ? columns.getColumnByName("course_code_id").getLongValue() : 0L;
		    			Long unit = columns.getColumnByName("unit_code_id") != null ? columns.getColumnByName("unit_code_id").getLongValue() : 0L;
		    			Long topic = columns.getColumnByName("topic_code_id") != null ? columns.getColumnByName("topic_code_id").getLongValue() : 0L;
		    			Long lesson = columns.getColumnByName("lesson_code_id") != null ? columns.getColumnByName("lesson_code_id").getLongValue() : 0L;
		    			if(subjectCode == 0L)
				    		subjectCode = subject;
				    		if(courseCode == 0L)
				    		courseCode = course;
				    		if(unitCode == 0L)
				    		unitCode = unit;
				    		if(topicCode == 0L)
				    		topicCode = topic ;
				    		if(lessonCode == 0L)
				    		lessonCode = lesson;
				    		conceptCode = value;
	    			}
	    			else if(value != null){
	    				taxArray.put(value);
	    				
	    			}
    		}else{
    			Long value = userInfo.getColumnByName("code_id") != null ?userInfo.getColumnByName("code_id").getLongValue() : 0L;
    			taxArray.put(value);
    		}
    	}
    		if(subjectCode != 0L && subjectCode != null)
    		eventMap.put("subject", subjectCode);
    		if(courseCode != 0L && courseCode != null)
    		eventMap.put("course", courseCode);
    		if(unitCode != 0L && unitCode != null)
    		eventMap.put("unit", unitCode);
    		if(topicCode != 0L && topicCode != null)
    		eventMap.put("topic", topicCode);
    		if(lessonCode != 0L && lessonCode != null)
    		eventMap.put("lesson", lessonCode);
    		if(conceptCode != 0L && conceptCode != null)
    		eventMap.put("concept", conceptCode);
    		if(taxArray != null && taxArray.toString() != null)
    		eventMap.put("standards", taxArray.toString());
    	
    	return eventMap;
    }
    
    public void postMigration(String startTime , String endTime,String customEventName) {
    	
    	ColumnList<String> settings = baseDao.readWithKey(ColumnFamily.CONFIGSETTINGS.getColumnFamily(), "ts_job_settings");
    	ColumnList<String> jobIds = baseDao.readWithKey(ColumnFamily.RECENTVIEWEDRESOURCES.getColumnFamily(), "job_ids");
    	
    	long jobCount = Long.valueOf(settings.getColumnByName("running_job_count").getStringValue());
    	long totalJobCount = Long.valueOf(settings.getColumnByName("total_job_count").getStringValue());
    	long maxJobCount = Long.valueOf(settings.getColumnByName("max_job_count").getStringValue());
    	long allowedCount = Long.valueOf(settings.getColumnByName("allowed_count").getStringValue());
    	long indexedCount = Long.valueOf(settings.getColumnByName("indexed_count").getStringValue());
    	long totalTime = Long.valueOf(settings.getColumnByName("total_time").getStringValue());
    	String runningJobs = jobIds.getColumnByName("job_names").getStringValue();
    		
    	if((jobCount < maxJobCount) && (indexedCount < allowedCount) ){
    		long start = System.currentTimeMillis();
    		long endIndex = Long.valueOf(settings.getColumnByName("max_count").getStringValue());
    		long startVal = Long.valueOf(settings.getColumnByName("indexed_count").getStringValue());
    		long endVal = (endIndex + startVal);
    		jobCount = (jobCount + 1);
    		totalJobCount = (totalJobCount + 1);
    		String jobId = "job-"+UUID.randomUUID();
    		
    		/*baseDao.saveStringValue(ColumnFamily.RECENTVIEWEDRESOURCES.getColumnFamily(), jobId, "start_count", ""+startVal);
    		baseDao.saveStringValue(ColumnFamily.RECENTVIEWEDRESOURCES.getColumnFamily(), jobId, "end_count", ""+endVal);
    		baseDao.saveStringValue(ColumnFamily.RECENTVIEWEDRESOURCES.getColumnFamily(), jobId, "job_status", "Inprogress");*/
    		baseDao.saveStringValue(ColumnFamily.CONFIGSETTINGS.getColumnFamily(), "ts_job_settings", "total_job_count", ""+totalJobCount);
    		baseDao.saveStringValue(ColumnFamily.CONFIGSETTINGS.getColumnFamily(), "ts_job_settings", "running_job_count", ""+jobCount);
    		baseDao.saveStringValue(ColumnFamily.CONFIGSETTINGS.getColumnFamily(), "ts_job_settings", "indexed_count", ""+endVal);
    		baseDao.saveStringValue(ColumnFamily.RECENTVIEWEDRESOURCES.getColumnFamily(), "job_ids", "job_names", runningJobs+","+jobId);
    		
    		Rows<String, String> resource = null;
    		MutationBatch m = null;
    		try {
    		m = getConnectionProvider().getKeyspace().prepareMutationBatch().setConsistencyLevel(DEFAULT_CONSISTENCY_LEVEL);

    		for(long i = startVal ; i < endVal ; i++){
    			logger.info("contentId : "+ i);
    				resource = baseDao.readIndexedColumn(ColumnFamily.DIMRESOURCE.getColumnFamily(), "content_id", i);
    				if(resource != null && resource.size() > 0){
    					
    					ColumnList<String> columns = resource.getRowByIndex(0).getColumns();
    					
    					logger.info("Gooru Id: {} = Views : {} ",columns.getColumnByName("gooru_oid").getStringValue(),columns.getColumnByName("views_count").getLongValue());
    					
    					baseDao.generateCounter(ColumnFamily.LIVEDASHBOARD.getColumnFamily(),"all~"+columns.getColumnByName("gooru_oid").getStringValue(), "count~views", columns.getColumnByName("views_count").getLongValue(), m);
    					baseDao.generateCounter(ColumnFamily.LIVEDASHBOARD.getColumnFamily(),"all~"+columns.getColumnByName("gooru_oid").getStringValue(), "time_spent~total", (columns.getColumnByName("views_count").getLongValue() * 4000), m);
    					
    				}
    			
    		}
    			m.execute();
    			long stop = System.currentTimeMillis();
    			
    		/*	baseDao.saveStringValue(ColumnFamily.RECENTVIEWEDRESOURCES.getColumnFamily(), jobId, "job_status", "Completed");
    			baseDao.saveStringValue(ColumnFamily.RECENTVIEWEDRESOURCES.getColumnFamily(), jobId, "run_time", (stop-start)+" ms");*/
    			baseDao.saveStringValue(ColumnFamily.CONFIGSETTINGS.getColumnFamily(), "ts_job_settings", "total_time", ""+(totalTime + (stop-start)));
    			baseDao.saveStringValue(ColumnFamily.CONFIGSETTINGS.getColumnFamily(), "ts_job_settings", "running_job_count", ""+(jobCount - 1));
    			
    		} catch (Exception e) {
    			e.printStackTrace();
    		}
    	}else{    		
    		logger.info("Job queue is full! Or Job Reached its allowed end");
    	}
		
    }
    
    public void catalogMigration(String startTime , String endTime,String customEventName) {
    	
    	ColumnList<String> settings = baseDao.readWithKey(ColumnFamily.CONFIGSETTINGS.getColumnFamily(), "cat_job_settings");
    	ColumnList<String> jobIds = baseDao.readWithKey(ColumnFamily.RECENTVIEWEDRESOURCES.getColumnFamily(), "job_ids");
    	
    	SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd kk:mm:ss+0000");
		SimpleDateFormat formatter2 = new SimpleDateFormat("yyyy-MM-dd kk:mm:ss");
    	
    	long jobCount = Long.valueOf(settings.getColumnByName("running_job_count").getStringValue());
    	long totalJobCount = Long.valueOf(settings.getColumnByName("total_job_count").getStringValue());
    	long maxJobCount = Long.valueOf(settings.getColumnByName("max_job_count").getStringValue());
    	long allowedCount = Long.valueOf(settings.getColumnByName("allowed_count").getStringValue());
    	long indexedCount = Long.valueOf(settings.getColumnByName("indexed_count").getStringValue());
    	long totalTime = Long.valueOf(settings.getColumnByName("total_time").getStringValue());
    	String runningJobs = jobIds.getColumnByName("job_names").getStringValue();
    		
    	if((jobCount < maxJobCount) && (indexedCount < allowedCount) ){
    		long start = System.currentTimeMillis();
    		long endIndex = Long.valueOf(settings.getColumnByName("max_count").getStringValue());
    		long startVal = Long.valueOf(settings.getColumnByName("indexed_count").getStringValue());
    		long endVal = (endIndex + startVal);
    		jobCount = (jobCount + 1);
    		totalJobCount = (totalJobCount + 1);
    		String jobId = "job-"+UUID.randomUUID();
    		
    	/*	baseDao.saveStringValue(ColumnFamily.RECENTVIEWEDRESOURCES.getColumnFamily(), jobId, "start_count", ""+startVal);
    		baseDao.saveStringValue(ColumnFamily.RECENTVIEWEDRESOURCES.getColumnFamily(), jobId, "end_count", ""+endVal);
    		baseDao.saveStringValue(ColumnFamily.RECENTVIEWEDRESOURCES.getColumnFamily(), jobId, "job_status", "Inprogress");*/
    		baseDao.saveStringValue(ColumnFamily.CONFIGSETTINGS.getColumnFamily(), "cat_job_settings", "total_job_count", ""+totalJobCount);
    		baseDao.saveStringValue(ColumnFamily.CONFIGSETTINGS.getColumnFamily(), "cat_job_settings", "running_job_count", ""+jobCount);
    		baseDao.saveStringValue(ColumnFamily.CONFIGSETTINGS.getColumnFamily(), "cat_job_settings", "indexed_count", ""+endVal);
    		baseDao.saveStringValue(ColumnFamily.RECENTVIEWEDRESOURCES.getColumnFamily(), "job_ids", "job_names", runningJobs+","+jobId);
    		
    		Rows<String, String> resource = null;
    		MutationBatch m = null;
    		try {
    		m = getConnectionProvider().getKeyspace().prepareMutationBatch().setConsistencyLevel(DEFAULT_CONSISTENCY_LEVEL);

    		for(long i = startVal ; i < endVal ; i++){
    			logger.info("contentId : "+ i);
    				resource = baseDao.readIndexedColumn(ColumnFamily.DIMRESOURCE.getColumnFamily(), "content_id", i);
    				if(resource != null && resource.size() > 0){
    					this.getResourceAndIndex(resource);
    				}
    			
    		}
    			m.execute();
    			long stop = System.currentTimeMillis();
    			
    			/*baseDao.saveStringValue(ColumnFamily.RECENTVIEWEDRESOURCES.getColumnFamily(), jobId, "job_status", "Completed");
    			baseDao.saveStringValue(ColumnFamily.RECENTVIEWEDRESOURCES.getColumnFamily(), jobId, "run_time", (stop-start)+" ms");*/
    			baseDao.saveStringValue(ColumnFamily.CONFIGSETTINGS.getColumnFamily(), "cat_job_settings", "total_time", ""+(totalTime + (stop-start)));
    			baseDao.saveStringValue(ColumnFamily.CONFIGSETTINGS.getColumnFamily(), "cat_job_settings", "running_job_count", ""+(jobCount - 1));
    			
    		} catch (Exception e) {
    			e.printStackTrace();
    		}
    	}else{    		
    		logger.info("Job queue is full! Or Job Reached its allowed end");
    	}
		
    }

    public void indexResource(String ids){
    	Collection<String> idList = new ArrayList<String>();
    	for(String id : ids.split(",")){
    		idList.add("GLP~" + id);
    	}
    	logger.info("resource id : {}",idList);
    	Rows<String,String> resource = baseDao.readWithKeyList(ColumnFamily.DIMRESOURCE.getColumnFamily(), idList);
    	try {
    		if(resource != null && resource.size() > 0){
    			this.getResourceAndIndex(resource);
    		}
		} catch (Exception e) {
			logger.info("indexing failed .. :{}",e);
		}
    }
    
    public void indexAnyCf(String sourceCf, String key, String targetIndex,String targetType) throws Exception{
    	for(String id : key.split(",")){
    		ColumnList<String> sourceValues = baseDao.readWithKey(ColumnFamily.DIMRESOURCE.getColumnFamily(), id);
	    	if(sourceValues != null && sourceValues.size() > 0){
	    		Collection<String> columnNames = sourceValues.getColumnNames();
	    		XContentBuilder contentBuilder = jsonBuilder().startObject();
	    		for(String columnName : columnNames){
	    			try{
	    				if(sourceValues.getStringValue(columnName, null) != null){
	    					contentBuilder.field(columnName,sourceValues.getStringValue(columnName, null));
	    				}
	    			}catch(Exception e){
	    				try{
	    					if(sourceValues.getLongValue(columnName, 0L) != null){
		    					contentBuilder.field(columnName,sourceValues.getLongValue(columnName, 0L));
	    					}
	    				}catch(Exception e1){
	    					try{
	    						if(sourceValues.getIntegerValue(columnName, 0) != null){
		    					contentBuilder.field(columnName,sourceValues.getIntegerValue(columnName, 0));
	    						}
	    					}catch(Exception e2){
		    					try{
			    					if(sourceValues.getBooleanValue(columnName, null) != null){
				    					contentBuilder.field(columnName,sourceValues.getBooleanValue(columnName, null));
				    				}
		    					}catch(Exception e3){
		    						try{
				    					if(sourceValues.getDoubleValue(columnName, null) != null){
					    					contentBuilder.field(columnName,sourceValues.getDoubleValue(columnName, null));
					    				}
		    						}catch(Exception e4){
		    							try{
		    								if(sourceValues.getDateValue(columnName, null) != null){
						    					contentBuilder.field(columnName,sourceValues.getDateValue(columnName, null));
						    				}
		    							}catch(Exception e5){
		    								logger.info("Exception while indexing : "+ e);
		    							}
		    						}
		    					}
		    				}
		    			}
	    			}
	    		}
	    		
	    		getESClient().prepareIndex(targetIndex, targetType, id).setSource(contentBuilder).execute().actionGet()
				
	    		;
	    	}
    	}
    }
    public void catalogMigrationCustom(String startTime , String endTime,String customEventName) {
    	
    	ColumnList<String> settings = baseDao.readWithKey(ColumnFamily.CONFIGSETTINGS.getColumnFamily(), "cat_job_settings");
    	ColumnList<String> jobIds = baseDao.readWithKey(ColumnFamily.RECENTVIEWEDRESOURCES.getColumnFamily(), "job_ids");
    	
    	SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd kk:mm:ss+0000");
		SimpleDateFormat formatter2 = new SimpleDateFormat("yyyy-MM-dd kk:mm:ss");
    	
    	long jobCount = Long.valueOf(settings.getColumnByName("running_job_count").getStringValue());
    	long totalJobCount = Long.valueOf(settings.getColumnByName("total_job_count").getStringValue());
    	long maxJobCount = Long.valueOf(settings.getColumnByName("max_job_count").getStringValue());
    	long allowedCount = Long.valueOf(settings.getColumnByName("allowed_count").getStringValue());
    	long indexedCount = Long.valueOf(settings.getColumnByName("indexed_count").getStringValue());
    	long totalTime = Long.valueOf(settings.getColumnByName("total_time").getStringValue());
    	String runningJobs = jobIds.getColumnByName("job_names").getStringValue();
    		
    	if((jobCount < maxJobCount) && (indexedCount < allowedCount) ){
    		long start = System.currentTimeMillis();
    		long endIndex = Long.valueOf(settings.getColumnByName("max_count").getStringValue());
    		long startVal = Long.valueOf(settings.getColumnByName("indexed_count").getStringValue());
    		long endVal = (endIndex + startVal);
    		jobCount = (jobCount + 1);
    		totalJobCount = (totalJobCount + 1);
    		String jobId = "job-"+UUID.randomUUID();
    		
    	/*	baseDao.saveStringValue(ColumnFamily.RECENTVIEWEDRESOURCES.getColumnFamily(), jobId, "start_count", ""+startVal);
    		baseDao.saveStringValue(ColumnFamily.RECENTVIEWEDRESOURCES.getColumnFamily(), jobId, "end_count", ""+endVal);
    		baseDao.saveStringValue(ColumnFamily.RECENTVIEWEDRESOURCES.getColumnFamily(), jobId, "job_status", "Inprogress");*/
    		baseDao.saveStringValue(ColumnFamily.CONFIGSETTINGS.getColumnFamily(), "cat_job_settings", "total_job_count", ""+totalJobCount);
    		baseDao.saveStringValue(ColumnFamily.CONFIGSETTINGS.getColumnFamily(), "cat_job_settings", "running_job_count", ""+jobCount);
    		baseDao.saveStringValue(ColumnFamily.CONFIGSETTINGS.getColumnFamily(), "cat_job_settings", "indexed_count", ""+endVal);
    		baseDao.saveStringValue(ColumnFamily.RECENTVIEWEDRESOURCES.getColumnFamily(), "job_ids", "job_names", runningJobs+","+jobId);
    		
    		Rows<String, String> resource = null;
    		MutationBatch m = null;
    		try {
    		m = getConnectionProvider().getKeyspace().prepareMutationBatch().setConsistencyLevel(DEFAULT_CONSISTENCY_LEVEL);

    		for(long i = startVal ; i < endVal ; i++){
    			logger.info("contentId : "+ i);
    				ColumnList<String> rowKey = baseDao.readWithKey("temp_scollection", ""+i, null);
    				if(rowKey.getStringValue("gooru_oid", null) != null){
    				resource = baseDao.readIndexedColumn(ColumnFamily.DIMRESOURCE.getColumnFamily(), "gooru_oid", rowKey.getStringValue("gooru_oid", null));
    				if(resource != null && resource.size() > 0){
    					this.getResourceAndIndex(resource);
    				}
    			}
    		}
    			m.execute();
    			long stop = System.currentTimeMillis();
    			
    			/*baseDao.saveStringValue(ColumnFamily.RECENTVIEWEDRESOURCES.getColumnFamily(), jobId, "job_status", "Completed");
    			baseDao.saveStringValue(ColumnFamily.RECENTVIEWEDRESOURCES.getColumnFamily(), jobId, "run_time", (stop-start)+" ms");*/
    			baseDao.saveStringValue(ColumnFamily.CONFIGSETTINGS.getColumnFamily(), "cat_job_settings", "total_time", ""+(totalTime + (stop-start)));
    			baseDao.saveStringValue(ColumnFamily.CONFIGSETTINGS.getColumnFamily(), "cat_job_settings", "running_job_count", ""+(jobCount - 1));
    			
    		} catch (Exception e) {
    			e.printStackTrace();
    		}
    	}else{    		
    		logger.info("Job queue is full! Or Job Reached its allowed end");
    	}
		
    }
    private void getResourceAndIndex(Rows<String, String> resource) throws ParseException{
    	SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd kk:mm:ss+0000");
		SimpleDateFormat formatter2 = new SimpleDateFormat("yyyy-MM-dd kk:mm:ss");
		SimpleDateFormat formatter3 = new SimpleDateFormat("yyyy/MM/dd kk:mm:ss.000");
		
		Map<String,Object> resourceMap = new LinkedHashMap<String, Object>();
		
		for(int a = 0 ; a < resource.size(); a++){
			
		ColumnList<String> columns = resource.getRowByIndex(a).getColumns();
		
		if(columns == null){
			return;
		}
		if(columns.getColumnByName("gooru_oid") != null){
			logger.info( " Migrating content : " + columns.getColumnByName("gooru_oid").getStringValue()); 
		}
		if(columns.getColumnByName("gooru_oid") == null){
			logger.info( " Columns size "+ columns.size());
		}
		if(columns.getColumnByName("title") != null){
			resourceMap.put("title", columns.getColumnByName("title").getStringValue());
		}
		if(columns.getColumnByName("description") != null){
			resourceMap.put("description", columns.getColumnByName("description").getStringValue());
		}
		if(columns.getColumnByName("gooru_oid") != null){
			resourceMap.put("gooruOid", columns.getColumnByName("gooru_oid").getStringValue());
		}
		if(columns.getColumnByName("last_modified") != null){
		try{
			resourceMap.put("lastModified", formatter.parse(columns.getColumnByName("last_modified").getStringValue()));
		}catch(Exception e){
			try{
				resourceMap.put("lastModified", formatter2.parse(columns.getColumnByName("last_modified").getStringValue()));
			}catch(Exception e2){
				resourceMap.put("lastModified", formatter3.parse(columns.getColumnByName("last_modified").getStringValue()));
			}
		}
		}
		if(columns.getColumnByName("created_on") != null){
		try{
			resourceMap.put("createdOn", columns.getColumnByName("created_on") != null  ? formatter.parse(columns.getColumnByName("created_on").getStringValue()) : formatter.parse(columns.getColumnByName("last_modified").getStringValue()));
		}catch(Exception e){
			try{
				resourceMap.put("createdOn", columns.getColumnByName("created_on") != null  ? formatter2.parse(columns.getColumnByName("created_on").getStringValue()) : formatter2.parse(columns.getColumnByName("last_modified").getStringValue()));
			}catch(Exception e2){
				resourceMap.put("createdOn", columns.getColumnByName("created_on") != null  ? formatter3.parse(columns.getColumnByName("created_on").getStringValue()) : formatter3.parse(columns.getColumnByName("last_modified").getStringValue()));
			}
		}
		}
		if(columns.getColumnByName("creator_uid") != null){
			resourceMap.put("creatorUid", columns.getColumnByName("creator_uid").getStringValue());
		}
		if(columns.getColumnByName("user_uid") != null){
			resourceMap.put("userUid", columns.getColumnByName("user_uid").getStringValue());
		}
		if(columns.getColumnByName("record_source") != null){
			resourceMap.put("recordSource", columns.getColumnByName("record_source").getStringValue());
		}
		if(columns.getColumnByName("sharing") != null){
			resourceMap.put("sharing", columns.getColumnByName("sharing").getStringValue());
		}
		if(columns.getColumnByName("views_count") != null){
			resourceMap.put("viewsCount", columns.getColumnByName("views_count").getLongValue());
		}
		if(columns.getColumnByName("organization_uid") != null){
			resourceMap.put("contentOrganizationUid", columns.getColumnByName("organization_uid").getStringValue());
		}
		if(columns.getColumnByName("thumbnail") != null){
			resourceMap.put("thumbnail", columns.getColumnByName("thumbnail").getStringValue());
		}
		if(columns.getColumnByName("grade") != null){
			JSONArray gradeArray = new JSONArray();
			for(String gradeId : columns.getColumnByName("grade").getStringValue().split(",")){
				gradeArray.put(gradeId);	
			}
			resourceMap.put("grade", gradeArray);
		}
		if(columns.getColumnByName("license_name") != null){
			//ColumnList<String> license = baseDao.readWithKey(ColumnFamily.LICENSE.getColumnFamily(), columns.getColumnByName("license_name").getStringValue());
			if(licenseCache.containsKey(columns.getColumnByName("license_name").getStringValue())){    							
				resourceMap.put("licenseId", licenseCache.get(columns.getColumnByName("license_name").getStringValue()));
			}
		}
		if(columns.getColumnByName("type_name") != null){
			//ColumnList<String> resourceType = baseDao.readWithKey(ColumnFamily.RESOURCETYPES.getColumnFamily(), columns.getColumnByName("type_name").getStringValue());
			if(resourceTypesCache.containsKey(columns.getColumnByName("type_name").getStringValue())){    							
				resourceMap.put("resourceTypeId", resourceTypesCache.get(columns.getColumnByName("type_name").getStringValue()));
			}
		}
		if(columns.getColumnByName("category") != null){
			//ColumnList<String> resourceType = baseDao.readWithKey(ColumnFamily.CATEGORY.getColumnFamily(), columns.getColumnByName("category").getStringValue());
			if(categoryCache.containsKey(columns.getColumnByName("category").getStringValue())){    							
				resourceMap.put("resourceCategoryId", categoryCache.get(columns.getColumnByName("category").getStringValue()));
			}
		}
		if(columns.getColumnByName("gooru_oid") != null){
			ColumnList<String> questionCount = baseDao.readWithKey(ColumnFamily.QUESTIONCOUNT.getColumnFamily(), columns.getColumnByName("gooru_oid").getStringValue());
			if(!questionCount.isEmpty() && columns.getColumnByName("type_name") != null){
				Long questionCounts = questionCount.getLongValue("questionCount", 0L);
				resourceMap.put("questionCount", questionCounts);
				if(questionCounts > 0L){
					if(resourceTypesCache.containsKey(columns.getColumnByName("type_name").getStringValue())){    							
						resourceMap.put("resourceTypeId", resourceTypesCache.get(columns.getColumnByName("type_name").getStringValue()));
					}	
				}
			}else{
				resourceMap.put("questionCount",0L);
			}
		}
		if(columns.getColumnByName("user_uid") != null){
			resourceMap = this.getUserInfo(resourceMap, columns.getColumnByName("user_uid").getStringValue());
		}
		if(columns.getColumnByName("gooru_oid") != null){
			resourceMap = this.getTaxonomyInfo(resourceMap, columns.getColumnByName("gooru_oid").getStringValue());
			liveDashBoardDAOImpl.saveInESIndex(resourceMap, ESIndexices.CONTENTCATALOGINFO.getIndex(), IndexType.DIMRESOURCE.getIndexType(), columns.getColumnByName("gooru_oid").getStringValue());
		}
		}
    }
    public void postStatMigration(String startTime , String endTime,String customEventName) {
    	
    	ColumnList<String> settings = baseDao.readWithKey(ColumnFamily.CONFIGSETTINGS.getColumnFamily(), "stat_job_settings");
    	ColumnList<String> jobIds = baseDao.readWithKey(ColumnFamily.RECENTVIEWEDRESOURCES.getColumnFamily(),"stat_job_ids");
    	
    	Collection<String> columnList = new ArrayList<String>();
    	columnList.add("count~views");
    	columnList.add("count~ratings");
    	
    	long jobCount = Long.valueOf(settings.getColumnByName("running_job_count").getStringValue());
    	long totalJobCount = Long.valueOf(settings.getColumnByName("total_job_count").getStringValue());
    	long maxJobCount = Long.valueOf(settings.getColumnByName("max_job_count").getStringValue());
    	long allowedCount = Long.valueOf(settings.getColumnByName("allowed_count").getStringValue());
    	long indexedCount = Long.valueOf(settings.getColumnByName("indexed_count").getStringValue());
    	long totalTime = Long.valueOf(settings.getColumnByName("total_time").getStringValue());
    	
    	String runningJobs = jobIds.getColumnByName("job_names").getStringValue();
    		
    	if((jobCount < maxJobCount) && (indexedCount < allowedCount) ){
    		long start = System.currentTimeMillis();
    		long endIndex = Long.valueOf(settings.getColumnByName("max_count").getStringValue());
    		long startVal = Long.valueOf(settings.getColumnByName("indexed_count").getStringValue());
    		long endVal = (endIndex + startVal);
    		jobCount = (jobCount + 1);
    		totalJobCount = (totalJobCount + 1);
    		String jobId = "job-"+UUID.randomUUID();
    		
			/*baseDao.saveStringValue(ColumnFamily.RECENTVIEWEDRESOURCES.getColumnFamily(), jobId, "start_count", ""+startVal);
			baseDao.saveStringValue(ColumnFamily.RECENTVIEWEDRESOURCES.getColumnFamily(), jobId, "end_count",  ""+endVal);
			baseDao.saveStringValue(ColumnFamily.RECENTVIEWEDRESOURCES.getColumnFamily(), jobId, "job_status", "Inprogress");*/
			baseDao.saveStringValue(ColumnFamily.RECENTVIEWEDRESOURCES.getColumnFamily(),"stat_job_ids", "job_names", runningJobs+","+jobId);
			baseDao.saveStringValue(ColumnFamily.CONFIGSETTINGS.getColumnFamily(),"stat_job_settings", "total_job_count", ""+totalJobCount);
			baseDao.saveStringValue(ColumnFamily.CONFIGSETTINGS.getColumnFamily(),"stat_job_settings", "running_job_count", ""+jobCount);
			baseDao.saveStringValue(ColumnFamily.CONFIGSETTINGS.getColumnFamily(),"stat_job_settings", "indexed_count", ""+endVal);
    		
    		
    		JSONArray resourceList = new JSONArray();
    		try {
	    		for(long i = startVal ; i <= endVal ; i++){
	    			logger.info("contentId : "+ i);
	    			String gooruOid = null;
	    			Rows<String, String> resource = baseDao.readIndexedColumn(ColumnFamily.DIMRESOURCE.getColumnFamily(), "content_id", i);
	    			if(resource != null && resource.size() > 0){
    					ColumnList<String> columns = resource.getRowByIndex(0).getColumns();
    					
    					String resourceType = columns.getColumnByName("type_name").getStringValue().equalsIgnoreCase("scollection") ? "scollection" : "resource";

    					gooruOid = columns.getColumnByName("gooru_oid").getStringValue(); 
	    				
    					logger.info("gooruOid : {}",gooruOid);
	    				
	    				if(gooruOid != null){
	    					long insightsView = 0L;
	    					long gooruView = columns.getLongValue("views_count", 0L);
	    					
	    					ColumnList<String> vluesList = baseDao.readWithKeyColumnList(ColumnFamily.LIVEDASHBOARD.getColumnFamily(),"all~"+gooruOid, columnList);
	    					JSONObject resourceObj = new JSONObject();
	    					for(Column<String> detail : vluesList) {
	    						resourceObj.put("gooruOid", gooruOid);
	    				
	    						if(detail.getName().contains("views")){
	    							insightsView = detail.getLongValue();
	    							long balancedView = (gooruView - insightsView);
	    							if(balancedView != 0){
	    								baseDao.increamentCounter(ColumnFamily.LIVEDASHBOARD.getColumnFamily(), "all~"+gooruOid, "count~views", balancedView);
	    							}
	    							logger.info("Generating resource Object : {}",balancedView);
	    							resourceObj.put("views", (insightsView + balancedView));
	    						}
	    						
	    						if(detail.getName().contains("ratings")){
	    							resourceObj.put("ratings", detail.getLongValue());
	    						}
	    						resourceObj.put("resourceType", resourceType);
	    					}
	    					resourceList.put(resourceObj);
	    				}
    				
	    			}
	    		}
	    		try{
	    			if((resourceList.length() != 0)){
	    				this.callStatAPI(resourceList, null);
	    			}
	    			long stop = System.currentTimeMillis();
	    			
	    			baseDao.saveStringValue(ColumnFamily.RECENTVIEWEDRESOURCES.getColumnFamily(), jobId, "job_status", "Completed");
	    			baseDao.saveStringValue(ColumnFamily.RECENTVIEWEDRESOURCES.getColumnFamily(), jobId, "run_time", (stop-start)+" ms");
	    			baseDao.saveStringValue(ColumnFamily.CONFIGSETTINGS.getColumnFamily(), "stat_job_settings", "total_time", ""+(totalTime + (stop-start)));
	    			baseDao.saveStringValue(ColumnFamily.CONFIGSETTINGS.getColumnFamily(), "stat_job_settings", "running_job_count", ""+(jobCount - 1));
	    			
	    		}catch(Exception e){
	    			logger.info("Error in search API : {}",e);
	    		}
	    		
    		} catch (Exception e) {
    			logger.info("Something went wrong : {}",e);
    		}
    	}else{    		
    		logger.info("Job queue is full!Or Reached maximum count!!");
    	}
		
    }
    
	public void balanceStatDataUpdate(){
		if(cache.get("balance_view_job").equalsIgnoreCase("stop")){
    		return;
    	}
		Calendar cal = Calendar.getInstance();
		try{
		MutationBatch m = getConnectionProvider().getKeyspace().prepareMutationBatch().setConsistencyLevel(DEFAULT_CONSISTENCY_LEVEL);
		ColumnList<String> settings = baseDao.readWithKey(ColumnFamily.CONFIGSETTINGS.getColumnFamily(), "bal_stat_job_settings");
		for (Long startDate = Long.parseLong(settings.getStringValue("last_updated_time", null)) ; startDate <= Long.parseLong(minuteDateFormatter.format(new Date()));) {
			JSONArray resourceList = new JSONArray();
			logger.info("Start Date : {} ",String.valueOf(startDate));
			ColumnList<String> recentReources =  baseDao.readWithKey(ColumnFamily.MICROAGGREGATION.getColumnFamily(),VIEWS+SEPERATOR+String.valueOf(startDate));
			Collection<String> gooruOids =  recentReources.getColumnNames();
			
			for(String id : gooruOids){
				ColumnList<String> insightsData = baseDao.readWithKey(ColumnFamily.LIVEDASHBOARD.getColumnFamily(), "all~"+id);
				ColumnList<String> gooruData = baseDao.readWithKey(ColumnFamily.DIMRESOURCE.getColumnFamily(), "GLP~"+id);
				long insightsView = 0L;
				long gooruView = 0L;
				if(insightsData != null){
					insightsView =   insightsData.getLongValue("count~views", 0L);
				}
				logger.info("insightsView : {} ",insightsView);
				if(gooruData != null){
					gooruView =  gooruData.getLongValue("views_count", 0L);
				}
				logger.info("gooruView : {} ",gooruView);
				long balancedView = (gooruView - insightsView);
				logger.info("Insights update views : {} ", (insightsView + balancedView) );
				baseDao.generateCounter(ColumnFamily.LIVEDASHBOARD.getColumnFamily(), "all~"+id, "count~views", balancedView, m);
				if(balancedView != 0){
					logger.info("Generating resource Object : {}",balancedView);
					JSONObject resourceObj = new JSONObject();
					resourceObj.put("gooruOid", id);
					resourceObj.put("views", (insightsView + balancedView));
					ColumnList<String> resource = baseDao.readWithKey(ColumnFamily.DIMRESOURCE.getColumnFamily(), "GLP~"+id);
	    			if(resource.getColumnByName("type_name") != null){
							String resourceType = resource.getColumnByName("type_name").getStringValue().equalsIgnoreCase("scollection") ? "scollection" : "resource";
							resourceObj.put("resourceType", resourceType);
					}
					resourceList.put(resourceObj);
				}
			}
				m.execute();
				if(resourceList.length() != 0){
					this.callStatAPI(resourceList, null);
				}
				
				baseDao.saveStringValue(ColumnFamily.CONFIGSETTINGS.getColumnFamily(), "bal_stat_job_settings", "last_updated_time", String.valueOf(startDate));
				
				cal.setTime(minuteDateFormatter.parse(String.valueOf(startDate)));
				cal.add(Calendar.MINUTE, 1);
				Date incrementedTime =cal.getTime(); 
				startDate = Long.parseLong(minuteDateFormatter.format(incrementedTime));
			}
    	}catch(Exception e){
			logger.info("Error in balancing view counts : {}",e);
		}
	
	}
    //Creating staging Events
    public HashMap<String, Object> createStageEvents(String minuteId,String hourId,String dateId,String eventId ,String userUid,ColumnList<String> eventDetails ,String eventDetailUUID) {
    	HashMap<String, Object> stagingEvents = new HashMap<String, Object>();
    	stagingEvents.put("minuteId", minuteId);
    	stagingEvents.put("hourId", hourId);
    	stagingEvents.put("dateId", dateId);
    	stagingEvents.put("eventId", eventId);
    	stagingEvents.put("userUid", userUid);
    	stagingEvents.put("contentGooruOid",eventDetails.getStringValue("content_gooru_oid", null));
    	stagingEvents.put("parentGooruOid",eventDetails.getStringValue("parent_gooru_oid", null));
    	stagingEvents.put("timeSpentInMillis",eventDetails.getLongValue("time_spent_in_millis", 0L));
    	stagingEvents.put("organizationUid",eventDetails.getStringValue("organization_uid", null));
    	stagingEvents.put("eventValue",eventDetails.getStringValue("event_value", null));
    	stagingEvents.put("resourceType",eventDetails.getStringValue("resource_type", null));
    	stagingEvents.put("appOid",eventDetails.getStringValue("app_oid", null));
    	stagingEvents.put("appUid",eventDetails.getStringValue("app_uid", null));
    	stagingEvents.put("city",eventDetails.getStringValue("city", null));
    	stagingEvents.put("state",eventDetails.getStringValue("state", null));
    	stagingEvents.put("country",eventDetails.getStringValue("country", null));
    	stagingEvents.put("attempt_number_of_try_sequence",eventDetails.getStringValue("attempt_number_of_try_sequence", null));
    	stagingEvents.put("attempt_first_status",eventDetails.getStringValue("attempt_first_status", null));
    	stagingEvents.put("answer_first_id",eventDetails.getStringValue("answer_first_id", null));
    	stagingEvents.put("attempt_status",eventDetails.getStringValue("attempt_status", null));
    	stagingEvents.put("attempt_try_sequence",eventDetails.getStringValue("attempt_try_sequence", null));
    	stagingEvents.put("answer_ids",eventDetails.getStringValue("answer_ids", null));
    	stagingEvents.put("open_ended_text",eventDetails.getStringValue("open_ended_text", null));
    	stagingEvents.put("keys",eventDetailUUID);
    	return stagingEvents; 
    }

    public void callAPIViewCount() throws JSONException {
    	if(cache.get("stat_job").equalsIgnoreCase("stop")){
    		logger.info("job stopped");
    		return;
    	}
    	JSONArray resourceList = new JSONArray();
    	String lastUpadatedTime = baseDao.readWithKeyColumn(ColumnFamily.CONFIGSETTINGS.getColumnFamily(), "views~last~updated", DEFAULTCOLUMN).getStringValue();
		String currentTime = minuteDateFormatter.format(new Date()).toString();
		Date lastDate = null;
		Date currDate = null;
		
		try {
			lastDate = minuteDateFormatter.parse(lastUpadatedTime);
			currDate = minuteDateFormatter.parse(currentTime);
		} catch (ParseException e1) {
			e1.printStackTrace();
		}		
		
		//Date rowValues = new Date(lastDate.getTime() + 60000);

		if((lastDate.getTime() <= currDate.getTime())){
			this.getRecordsToProcess(lastDate, resourceList);
			logger.info("processing mins : {} , {} ",minuteDateFormatter.format(lastDate),minuteDateFormatter.format(lastDate));
		}else{
			logger.info("processing min : {} ",currDate);
			this.getRecordsToProcess(currDate, resourceList);
		}
   }
  private void getRecordsToProcess(Date rowValues,JSONArray resourceList) throws JSONException{
	  int indexedCount = 0;
	  int indexedLimit = 2;
	  int allowedLimit = 0;
		ColumnList<String> contents = baseDao.readWithKey(ColumnFamily.MICROAGGREGATION.getColumnFamily(),VIEWS+SEPERATOR+minuteDateFormatter.format(rowValues));
		ColumnList<String> indexedCountList = baseDao.readWithKey(ColumnFamily.MICROAGGREGATION.getColumnFamily(),VIEWS+SEPERATOR+"index~count");
		indexedCount = indexedCountList != null ? Integer.valueOf(indexedCountList.getStringValue(minuteDateFormatter.format(rowValues), "0")) : 0;
		logger.info("1:-> size : " + contents.size() + "indexed count : " + indexedCount);
		if(indexedCount == (contents.size() - 1)){
		 rowValues = new Date(rowValues.getTime() + 60000);
		 contents = baseDao.readWithKey(ColumnFamily.MICROAGGREGATION.getColumnFamily(),VIEWS+SEPERATOR+minuteDateFormatter.format(rowValues));
		 logger.info("2:-> size : " + contents.size() + "indexed count : " + indexedCount);
		}
		if(contents.size() > 0 ){
			ColumnList<String> IndexLimitList = baseDao.readWithKey(ColumnFamily.CONFIGSETTINGS.getColumnFamily(),"index~limit");
			indexedLimit = IndexLimitList != null ? Integer.valueOf(IndexLimitList.getStringValue(DEFAULTCOLUMN, "0")) : 2;
			allowedLimit = (indexedCount + indexedLimit);
			if(allowedLimit > contents.size() ){
				allowedLimit = indexedCount + (contents.size() - indexedCount) ;
			}
			logger.info("3:-> indexedCount : " + indexedCount + "allowedLimit : " + allowedLimit);
			
			for(int i = indexedCount ; i < allowedLimit ; i++) {
				indexedCount = i;
				ColumnList<String> vluesList = baseDao.readWithKeyColumnList(ColumnFamily.LIVEDASHBOARD.getColumnFamily(),"all~"+contents.getColumnByIndex(i).getName(), statKeys);
				for(Column<String> detail : vluesList) {
					JSONObject resourceObj = new JSONObject();
					resourceObj.put("gooruOid", contents.getColumnByIndex(i).getStringValue());
					ColumnList<String> resource = baseDao.readWithKey(ColumnFamily.DIMRESOURCE.getColumnFamily(), "GLP~"+contents.getColumnByIndex(i).getStringValue());
	    			if(resource.getColumnByName("type_name") != null){
							String resourceType = resource.getColumnByName("type_name").getStringValue().equalsIgnoreCase("scollection") ? "scollection" : "resource";
							resourceObj.put("resourceType", resourceType);
					}
					for(String column : statKeys){
						if(detail.getName().equals(column)){
							logger.info("statValuess : {}",statMetrics.getStringValue(column, null));
							resourceObj.put(statMetrics.getStringValue(column, null), detail.getLongValue());
						}
					}
					if(resourceObj.length() > 0 ){
						resourceList.put(resourceObj);
					}
				}
			}
		}
		if((resourceList.length() != 0)){
			this.callStatAPI(resourceList, rowValues);			
			baseDao.saveStringValue(ColumnFamily.MICROAGGREGATION.getColumnFamily(), VIEWS+SEPERATOR+"index~count", minuteDateFormatter.format(rowValues) ,String.valueOf(indexedCount),86400);

		}else{
			baseDao.saveStringValue(ColumnFamily.CONFIGSETTINGS.getColumnFamily(), "views~last~updated", DEFAULTCOLUMN, minuteDateFormatter.format(rowValues));
	 		logger.info("No content viewed");
		}
	 
  }
    
    private void callStatAPI(JSONArray resourceList,Date rowValues){
    	JSONObject staticsObj = new JSONObject();
		String sessionToken = baseDao.readWithKeyColumn(ColumnFamily.CONFIGSETTINGS.getColumnFamily(),LoaderConstants.SESSIONTOKEN.getName(), DEFAULTCOLUMN).getStringValue();
		try{
				String url = cache.get(VIEWUPDATEENDPOINT) + "?skipReindex=false&sessionToken=" + sessionToken;
				DefaultHttpClient httpClient = new DefaultHttpClient();   
				staticsObj.put("statisticsData", resourceList);
				logger.info("staticsObj : {}",staticsObj);
				StringEntity input = new StringEntity(staticsObj.toString());			        
		 		HttpPost  postRequest = new HttpPost(url);
		 		logger.info("staticsObj : {}",url);
		 		postRequest.addHeader("accept", "application/json");
		 		postRequest.setEntity(input);
		 		HttpResponse response = httpClient.execute(postRequest);
		 		logger.info("Status : {} ",response.getStatusLine().getStatusCode());
		 		logger.info("Reason : {} ",response.getStatusLine().getReasonPhrase());
		 		if (response.getStatusLine().getStatusCode() != 200) {
		 	 		logger.info("View count api call failed...");
		 	 		throw new AccessDeniedException("Something went wrong! Api fails");
		 		} else {
		 			if(rowValues != null){
		 				baseDao.saveStringValue(ColumnFamily.CONFIGSETTINGS.getColumnFamily(), "views~last~updated", DEFAULTCOLUMN, minuteDateFormatter.format(rowValues));
		 			}
		 	 		logger.info("View count api call Success...");
		 		}
		 			
		} catch(Exception e){
			e.printStackTrace();
		}		
	
    }
    
     public void updateActivityCompletion(String userUid, ColumnList<String> activityRow, String eventId, Map<String, Object> timeMap){
       	Long startTime = activityRow.getLongValue(START_TIME, 0L), endTime = activityRow.getLongValue(END_TIME, 0L);
       	String eventType = null;
       	JsonElement jsonElement = null;
       	JsonObject existingEventObj = null;
       	String existingColumnName = null;
       	
       	if (activityRow.getStringValue(EVENT_TYPE, null) != null){
       		eventType = activityRow.getStringValue(EVENT_TYPE, null);
       	}
       	
           long timeInMillisecs = 0L;
           if (endTime != null && startTime != null) {
               timeInMillisecs = endTime - startTime;
           }

           if (!StringUtils.isEmpty(eventType) && userUid != null) {
       		Map<String,Object> existingRecord = baseDao.isEventIdExists(ColumnFamily.ACITIVITYSTREAM.getColumnFamily(),userUid, eventId);
       		if(existingRecord.get("isExists").equals(true) && existingRecord.get("jsonString").toString() != null) {
   			    jsonElement = new JsonParser().parse(existingRecord.get("jsonString").toString());
   				existingEventObj = jsonElement.getAsJsonObject();
   			    if ("completed-event".equalsIgnoreCase(eventType) || "stop".equalsIgnoreCase(eventType)) {
   					existingColumnName = existingRecord.get("existingColumnName").toString();
   				    startTime = existingEventObj.get(START_TIME).getAsLong();
   			    } else {
   				    endTime = existingEventObj.get(END_TIME).getAsLong();
   			    }
       		}
       		
   			// Time taken for the event in milliseconds derived from the start / stop events.
   			if (endTime != null && startTime != null) {
   				timeInMillisecs = endTime - startTime;
   			}
   			if (timeInMillisecs > 1147483647) {
   			    // When time in Milliseconds is very very huge, set to min time to serve the call.
   			    timeInMillisecs = 30;
   			    // Since this is an error condition, log it.
   			}
           }
           timeMap.put("startTime", startTime);
           timeMap.put("endTime", endTime);
           timeMap.put("event_type", eventType);
           timeMap.put("existingColumnName", existingColumnName);
           timeMap.put("timeSpent", timeInMillisecs);    
           
       }

    /**
     * @return the connectionProvider
     */
    public CassandraConnectionProvider getConnectionProvider() {
    	return connectionProvider;
    }
    
    public void executeForEveryMinute(String startTime,String endTime){
    	logger.debug("start the static loader");
    	JSONObject jsonObject = new JSONObject();
    	try {
			jsonObject.put("startTime",startTime );
			jsonObject.put("endTime",endTime );
		} catch (JSONException e) {
			e.printStackTrace();
		}
    	microAggregator.sendEventForStaticAggregation(jsonObject.toString());
    }
    
    public void watchSession(){
    	try {
			liveDashBoardDAOImpl.watchApplicationSession();
		} catch (ParseException e) {
			logger.info("Exception : {} ",e);
		}
    }
    
    public boolean createEvent(String eventName,String apiKey){

    	Map<String,Object> records = new HashMap<String, Object>();
    	apiKey = apiKey != null ? apiKey : DEFAULT_API_KEY;
		records.put("api_key", apiKey);
		records.put("event_name", eventName);
    	if(baseDao.isValueExists(ColumnFamily.DIMEVENTS.getColumnFamily(),records)){
				 
				UUID eventId = TimeUUIDUtils.getUniqueTimeUUIDinMillis();
				records.put("event_id", eventId.toString());
				String key = apiKey+SEPERATOR+eventId.toString();
				 baseDao.saveBulkList(ColumnFamily.DIMEVENTS.getColumnFamily(),key,records);
		return true;
    	}
    	return false;
    }
    
	public boolean validateSchedular(String ipAddress) {

		try {
			//ColumnList<String> columnList = configSettings.getColumnList("schedular~ip");
			ColumnList<String> columnList = baseDao.readWithKey(ColumnFamily.CONFIGSETTINGS.getColumnFamily(),"schedular~ip");
			String configuredIp = columnList.getColumnByName("ip_address").getStringValue();
			if (configuredIp != null) {
				if (configuredIp.equalsIgnoreCase(ipAddress))
					return true;
			}
		} catch (Exception e) {
			logger.error(" unable to get the scedular IP " + e);
			return false;
		}
		return false;
	}
    
    private Map<String,String> formatEventMap(EventObject eventObject,Map<String,String> eventMap){
    	
    	String userUid = null;
    	String organizationUid = DEFAULT_ORGANIZATION_UID;
    	eventObject.setParentGooruId(eventMap.get("parentGooruId"));
    	eventObject.setContentGooruId(eventMap.get("contentGooruId"));
    	if(eventMap.containsKey("parentEventId") && eventMap.get("parentEventId") != null){
    		eventObject.setParentEventId(eventMap.get("parentEventId"));
    	}
    	eventObject.setTimeInMillSec(Long.parseLong(eventMap.get("totalTimeSpentInMs")));
    	eventObject.setEventType(eventMap.get("type"));
    	
    	if (eventMap != null && eventMap.get("gooruUId") != null && eventMap.containsKey("organizationUId") && (eventMap.get("organizationUId") == null ||  eventMap.get("organizationUId").isEmpty())) {
				 try {
					 userUid = eventMap.get("gooruUId");
					 Rows<String, String> userDetails = baseDao.readIndexedColumn(ColumnFamily.DIMUSER.getColumnFamily(), "gooru_uid", userUid);
	   					for(Row<String, String> userDetail : userDetails){
	   						organizationUid = userDetail.getColumns().getStringValue("organization_uid", null);
	   					}
					 eventObject.setOrganizationUid(organizationUid);
			    	 JSONObject sessionObj = new JSONObject(eventObject.getSession());
			    	 sessionObj.put("organizationUId", organizationUid);
			    	 eventObject.setSession(sessionObj.toString());
			    	 JSONObject fieldsObj = new JSONObject(eventObject.getFields());
			    	 fieldsObj.put("session", sessionObj.toString());
			    	 eventObject.setFields(fieldsObj.toString());
			    	 eventMap.put("organizationUId", organizationUid);
				 } catch (Exception e) {
						logger.info("Error while fetching User uid ");
				 }
			 }
    	eventMap.put("eventName", eventObject.getEventName());
    	eventMap.put("eventId", eventObject.getEventId());
    	eventMap.put("startTime",String.valueOf(eventObject.getStartTime()));
    	
    	return eventMap;
    }

    private void getAndSetAnswerStatus(EventData eventData){
    	if(eventData.getEventName().equalsIgnoreCase(LoaderConstants.CQRPD.getName()) || eventData.getEventName().equalsIgnoreCase(LoaderConstants.QPD.getName())){
    		String answerStatus = null;
    			if(eventData.getAttemptStatus().length == 0){
    				answerStatus = LoaderConstants.SKIPPED.getName();
    				eventData.setAttemptFirstStatus(answerStatus);
    				eventData.setAttemptNumberOfTrySequence(eventData.getAttemptTrySequence().length);
    			}else {
	    			if(eventData.getAttemptStatus()[0] == 1){
	    				answerStatus = LoaderConstants.CORRECT.getName();
	    			}else if(eventData.getAttemptStatus()[0] == 0){
	    				answerStatus = LoaderConstants.INCORRECT.getName();
	    			}
	    			eventData.setAttemptFirstStatus(answerStatus);
    				eventData.setAttemptNumberOfTrySequence(eventData.getAttemptTrySequence().length);
    				eventData.setAnswerFirstId(eventData.getAnswerId()[0]);
    			}
    			
    	}
    }

    private void updateEventCompletion(EventData eventData) throws ConnectionException {

    	Long endTime = eventData.getEndTime(), startTime = eventData.getStartTime();
        long timeInMillisecs = 0L;
        if (endTime != null && startTime != null) {
            timeInMillisecs = endTime - startTime;
        }
        boolean eventComplete = false;

        eventData.setTimeInMillSec(timeInMillisecs);

        if (StringUtils.isEmpty(eventData.getEventId())) {
            return;
        }

        if (StringUtils.isEmpty(eventData.getEventType()) && !StringUtils.isEmpty(eventData.getType())) {
            eventData.setEventType(eventData.getType());
        }

        if (!StringUtils.isEmpty(eventData.getEventType())) {
			ColumnList<String> existingRecord = baseDao.readWithKey(ColumnFamily.EVENTDETAIL.getColumnFamily(),eventData.getEventId());
			if (existingRecord != null && !existingRecord.isEmpty()) {
			    if ("stop".equalsIgnoreCase(eventData.getEventType())) {
			        startTime = existingRecord.getLongValue("start_time", null);
			        //Update startTime with existingRecord, IF existingRecord.startTime < startTime
			    } else {
			        endTime = existingRecord.getLongValue("end_time", null);
			        // Update endTime with existing record IF existingRecord.endTime > endTime
			    }
			    eventComplete = true;
			}
			// Time taken for the event in milliseconds derived from the start / stop events.
			if (endTime != null && startTime != null) {
				timeInMillisecs = endTime - startTime;
			}
			if (timeInMillisecs > 1147483647) {
			    // When time in Milliseconds is very very huge, set to min time to serve the call.
			    timeInMillisecs = 30;
			    // Since this is an error condition, log it.
			}
        }

        eventData.setStartTime(startTime);
        eventData.setEndTime(endTime);

        if (eventComplete) {
            eventData.setTimeInMillSec(timeInMillisecs);
            eventData.setEventType("completed-event");
            eventData.setEndTime(endTime);
            eventData.setStartTime(startTime);
        }

        if(!StringUtils.isEmpty(eventData.getParentEventId())){
        	ColumnList<String> existingParentRecord = baseDao.readWithKey(ColumnFamily.EVENTDETAIL.getColumnFamily(),eventData.getParentEventId());
        	if (existingParentRecord != null && !existingParentRecord.isEmpty()) {
        		Long parentStartTime = existingParentRecord.getLongValue("start_time", null);
        		baseDao.saveLongValue(ColumnFamily.EVENTDETAIL.getColumnFamily(), eventData.getParentEventId(), "end_time", endTime);
        		baseDao.saveLongValue(ColumnFamily.EVENTDETAIL.getColumnFamily(), eventData.getParentEventId(), "time_spent_in_millis", (endTime-parentStartTime));
        		
        	}
        }

    }

 private void updateActivityStream(String eventId) throws JSONException {
       	
       	if (eventId != null){

   	    	Map<String,String> rawMap = new HashMap<String, String>();
   		    String apiKey = null;
   	    	String userName = null;
   	    	String dateId = null;
   	    	String userUid = null;
   	    	String contentGooruId = null;
   	    	String parentGooruId = null;
   	    	String organizationUid = null;
   	    	Date endDate = new Date();
   	    	
   	    	ColumnList<String> activityRow = baseDao.readWithKey(ColumnFamily.EVENTDETAIL.getColumnFamily(), eventId);	
   	    	if (activityRow != null){
   	    	String fields = activityRow.getStringValue(FIELDS, null);
  	         	
   	    	SimpleDateFormat minuteDateFormatter = new SimpleDateFormat(MINUTEDATEFORMATTER);
   	    	HashMap<String, Object> activityMap = new HashMap<String, Object>();
   	    	Map<String, Object> eventMap = new HashMap<String, Object>();       
   	    	if(activityRow.getLongValue(END_TIME, null) != null) {
   	    		endDate = new Date(activityRow.getLongValue(END_TIME, null));
   	    	} else {
   	    		endDate = new Date(activityRow.getLongValue(START_TIME, null));
   	    	}
       		dateId = minuteDateFormatter.format(endDate).toString();
   			Map<String , Object> timeMap = new HashMap<String, Object>();

   			//Get userUid
   			if (rawMap != null && rawMap.get("gooruUId") != null) {
   				 try {
   					 userUid = rawMap.get("gooruUId");
   					Rows<String, String> userDetails = baseDao.readIndexedColumn(ColumnFamily.DIMUSER.getColumnFamily(), "gooru_uid", userUid);
   					for(Row<String, String> userDetail : userDetails){
   						userName = userDetail.getColumns().getStringValue("username", null);
   					}
   				 } catch (Exception e) {
   						logger.info("Error while fetching User uid ");
   				 }
   			 } else if (activityRow.getStringValue("gooru_uid", null) != null) {
   				try {
   					 userUid = activityRow.getStringValue("gooru_uid", null);
   					Rows<String, String> userDetails = baseDao.readIndexedColumn(ColumnFamily.DIMUSER.getColumnFamily(), "gooru_uid", activityRow.getStringValue("gooru_uid", null));
   					for(Row<String, String> userDetail : userDetails){
   						userName = userDetail.getColumns().getStringValue("username", null);
   					}
   				} catch (Exception e) {
   					logger.info("Error while fetching User uid ");
   				}			
   			 } else if (activityRow.getStringValue("user_id", null) != null) {
   				 try {
   					ColumnList<String> userUidList = baseDao.readWithKey(ColumnFamily.DIMUSER.getColumnFamily(), activityRow.getStringValue("user_id", null));
					userUid = userUidList.getStringValue("gooru_uid", null);
					
   					Rows<String, String> userDetails = baseDao.readIndexedColumn(ColumnFamily.DIMUSER.getColumnFamily(), "gooru_uid", activityRow.getStringValue("gooru_uid", null));
   					for(Row<String, String> userDetail : userDetails){
   						userName = userDetail.getColumns().getStringValue("username", null);
   					}						
   				} catch (Exception e) {
   					logger.info("Error while fetching User uid ");
   				}
   			 } 	
   			if(userUid != null && eventId != null){
   				logger.info("userUid {} ",userUid);
   				this.updateActivityCompletion(userUid, activityRow, eventId, timeMap);
   			} else {
   				return;
   			}

   		    if(rawMap != null && rawMap.get(APIKEY) != null) {
   		    	apiKey = rawMap.get(APIKEY);
   		    } else if(activityRow.getStringValue(APIKEY, null) != null){
   		    	apiKey = activityRow.getStringValue(APIKEY, null);
   		    }
   		    if(rawMap != null && rawMap.get(CONTENTGOORUOID) != null){
   		    	contentGooruId = rawMap.get(CONTENTGOORUOID);
   		    } else if(activityRow.getStringValue(CONTENT_GOORU_OID, null) != null){
   		    	contentGooruId = activityRow.getStringValue(CONTENT_GOORU_OID, null);
   		    }
   		    if(rawMap != null && rawMap.get(PARENTGOORUOID) != null){
   		    	parentGooruId = rawMap.get(PARENTGOORUOID);
   		    } else if(activityRow.getStringValue(PARENT_GOORU_OID, null) != null){
   		    	parentGooruId = activityRow.getStringValue(PARENT_GOORU_OID, null);
   		    }
   		    if(rawMap != null && rawMap.get(ORGANIZATIONUID) != null){
   		    	organizationUid = rawMap.get(ORGANIZATIONUID);
   		    } else if (activityRow.getStringValue("organization_uid", null) != null){
   		    	organizationUid = activityRow.getStringValue("organization_uid", null);
   		    }
   	    	activityMap.put("eventId", eventId);
   	    	activityMap.put("eventName", activityRow.getStringValue(EVENT_NAME, null));
   	    	activityMap.put("userUid",userUid);
   	    	activityMap.put("dateId", dateId);
   	    	activityMap.put("userName", userName);
   	    	activityMap.put("apiKey", apiKey);
   	    	activityMap.put("organizationUid", organizationUid);
   	        activityMap.put("existingColumnName", timeMap.get("existingColumnName"));
   	        
   	    	eventMap.put("start_time", timeMap.get("startTime"));
   	    	eventMap.put("end_time", timeMap.get("endTime"));
   	    	eventMap.put("event_type", timeMap.get("event_type"));
   	        eventMap.put("timeSpent", timeMap.get("timeSpent"));
   	
   	    	eventMap.put("user_uid",userUid);
   	    	eventMap.put("username",userName);
   	    	eventMap.put("raw_data",activityRow.getStringValue(FIELDS, null));
   	    	eventMap.put("content_gooru_oid", contentGooruId);
   	    	eventMap.put("parent_gooru_oid", parentGooruId);
   	    	eventMap.put("organization_uid", organizationUid);
   	    	eventMap.put("event_name", activityRow.getStringValue(EVENT_NAME, null));
   	    	eventMap.put("event_value", activityRow.getStringValue(EVENT_VALUE, null));
   	
	    	eventMap.put("event_id", eventId);
	    	eventMap.put("api_key",apiKey);
	    	eventMap.put("organization_uid",organizationUid);
	    	
   	    	activityMap.put("activity", new JSONSerializer().serialize(eventMap));
   	    	
   	    	if(userUid != null){
   	    		baseDao.saveActivity(ColumnFamily.ACITIVITYSTREAM.getColumnFamily(), activityMap);
   	    	}
   	    	}
       	}
   	}
 
    @Async
    private String updateEvent(EventData eventData) {
    	ColumnList<String> apiKeyValues = baseDao.readWithKey(ColumnFamily.APIKEY.getColumnFamily(),eventData.getApiKey());
        String appOid = apiKeyValues.getStringValue("app_oid", null);
        if(eventData.getTimeSpentInMs() != null){
	          eventData.setTimeInMillSec(eventData.getTimeSpentInMs());
	     }
        return baseDao.saveEvent(ColumnFamily.EVENTDETAIL.getColumnFamily(),eventData);
    }    
    /**
     * @param connectionProvider the connectionProvider to set
     */
    public void setConnectionProvider(CassandraConnectionProvider connectionProvider) {
    	this.connectionProvider = connectionProvider;
    }

    
}

  
