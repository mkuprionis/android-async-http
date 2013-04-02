/*
    Android Asynchronous Http Client
    Copyright (c) 2011 James Smith <james@loopj.com>
    http://loopj.com

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
*/

package com.loopj.android.http;

import java.io.IOException;
import java.net.UnknownHostException;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpRequestRetryHandler;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.AbstractHttpClient;
import org.apache.http.protocol.HttpContext;

import android.util.Log;

class AsyncHttpRequest implements Runnable {
	private static final String TAG = "HTTP/Request";
	
	private final AbstractHttpClient client;
    private final HttpContext context;
    
    // Volatile, because it can be accessed from another
    // thread to call request.abort(). Not sure what's better 
    // and what are the implications when request is final or
    // or volatile.
    private volatile HttpUriRequest request;
    private final AsyncHttpResponseHandler responseHandler;
    private int executionCount;

    private volatile boolean isCanceled;
    
    // Whether we're in development environment
    private boolean debug = false;
    
    public AsyncHttpRequest(AbstractHttpClient client, HttpContext context, HttpUriRequest request, AsyncHttpResponseHandler responseHandler) {
        this.client = client;
        this.context = context;
        this.request = request;
        this.responseHandler = responseHandler;
        this.isCanceled = false;
    }

    public void run() {
        if (responseHandler != null && !isCanceled) {
        	responseHandler.setDebug(debug);
        	if(debug) {
        		responseHandler.setRequestLine(request.getRequestLine().toString());
        	}
        	
            responseHandler.sendStartMessage();
        }
        
        long startTime = 0;
        if(debug) startTime = System.currentTimeMillis();
        
        try {
            makeRequestWithRetries();
        } catch (IOException e) {
            if (responseHandler != null && !isCanceled) {
                responseHandler.sendFailureMessage(0, null, e);
            }
        }
        
        if(debug) Log.d(TAG, String.format("Completed in %dms: %s\n", System.currentTimeMillis() - startTime, request.getRequestLine()));
    	
    	if (responseHandler != null && !isCanceled) {
            responseHandler.sendFinishMessage();
        }
    	
    	// When all is done, explicitly close request
    	// so that connections are released.
    	request.abort();
    }

    public void cancel() {
    	isCanceled = true;
    	
    	if(responseHandler != null) {
    		responseHandler.cancel();
    	}
    	
    	// `request.abort()` has to be called NOT on UI thread,
    	// because if call is on UI thread, Android strict mode
    	// will forbid operation with Fatal Error "Network not allowed
    	// on UI thread" - because request abortion is a network operation.
    	//
    	// However cancel() method will usually be called on UI thread,
    	// thus we need to make request.abort() call on another tread.
    	// 
    	// Creating a new thread just to call abort() method is kinda 
    	// heavy handed solution, something more elegant would be nice
		new Thread(new Runnable() {
			@Override public void run() {
				try {
					request.abort();
				} catch(UnsupportedOperationException e) {
		    	}
            }
		}).start();
    	
    }
    
    /**
     * Setting to `true` will turn on verbose logging
     * @param isDevelopmentEnv
     */
    public void setDebug(boolean isDevelopmentEnv) {
    	this.debug = isDevelopmentEnv;
    }
    
    private void makeRequest() throws IOException {
        if(!isCanceled) {
            HttpResponse response = client.execute(request, context);
            
            if(debug) Log.v(TAG, String.format("Finished executing %s", request.getRequestLine()));
            
            if(responseHandler != null && !isCanceled) {
            	if(debug) Log.v(TAG, String.format("About to send response message for %s", request.getRequestLine()));
                responseHandler.sendResponseMessage(response);
            } else {
            	if(debug) Log.v(TAG, String.format("Not sending response message {handler=%s; isCanceled=%b} for %s", (responseHandler != null ? responseHandler.toString() : "null"), isCanceled, request.getRequestLine()));
            }
        }
    }

    private void makeRequestWithRetries() throws IOException {
        // This is an additional layer of retry logic lifted from droid-fu
        // See: https://github.com/kaeppler/droid-fu/blob/master/src/main/java/com/github/droidfu/http/BetterHttpRequestBase.java
        boolean retry = true;
        IOException cause = null;
        HttpRequestRetryHandler retryHandler = client.getHttpRequestRetryHandler();
        try
        {
            while (retry) {
                try {
                    makeRequest();
                    return;
                } catch (UnknownHostException e) {
                    // switching between WI-FI and mobile data networks can cause a retry which then results in an UnknownHostException
                    // while the WI-FI is initialising. The retry logic will be invoked here, if this is NOT the first retry
                    // (to assist in genuine cases of unknown host) which seems better than outright failure
                    cause = new IOException("UnknownHostException exception: " + e.getMessage());
                    retry = (executionCount > 0) && retryHandler.retryRequest(cause, ++executionCount, context);
                } catch (IOException e) {
                    cause = e;
                    retry = retryHandler.retryRequest(cause, ++executionCount, context);
                } catch (NullPointerException e) {
                    // there's a bug in HttpClient 4.0.x that on some occasions causes
                    // DefaultRequestExecutor to throw an NPE, see
                    // http://code.google.com/p/android/issues/detail?id=5255
                    cause = new IOException("NPE in HttpClient: " + e.getMessage());
                    retry = retryHandler.retryRequest(cause, ++executionCount, context);
                }
                
                if(debug) Log.d(TAG, String.format("Request failed because of %s: %s", cause.getClass().getSimpleName(), request.getRequestLine()));
                
                if(retry && (responseHandler != null)) {
                	if(debug) Log.d(TAG, String.format("Retrying (%d) %s", executionCount, request.getRequestLine()));
                	responseHandler.sendRetryMessage();
                }
            }
        } catch (Exception e) {
            // catch anything else to ensure failure message is propagated
            cause = new IOException("Unhandled exception: " + e.getMessage());
        }
        
        // cleaned up to throw IOException
        throw(cause);
    }
}
