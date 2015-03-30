/*******************************************************************************
 * EventObjectParser.java
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

import java.util.Map;

import org.ednovo.data.model.EventData;
import org.ednovo.data.model.Event;
import org.json.JSONException;


public class EventParser  {

    private CassandraConnectionProvider connectionProvider;

	public EventParser() {
        this(null);
    }

    public EventParser(Map<String, String> configOptionsMap) {
        init(configOptionsMap);
    }
    private void init(Map<String, String> configOptionsMap) {
    	this.setConnectionProvider(new CassandraConnectionProvider());
        this.getConnectionProvider().init(configOptionsMap);
        //To intialize columnFamlies in future
    }

	public void parseObject(EventData eventData , Event eventObject) throws JSONException {

	
	}
	
	  /**
     * @return the connectionProvider
     */
    public CassandraConnectionProvider getConnectionProvider() {
    	return connectionProvider;
    }
    
    /**
     * @param connectionProvider the connectionProvider to set
     */
    public void setConnectionProvider(CassandraConnectionProvider connectionProvider) {
    	this.connectionProvider = connectionProvider;
    }
  
}