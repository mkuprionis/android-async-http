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

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.HttpResponseException;
import org.apache.http.util.ByteArrayBuffer;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.io.InputStream;
import android.util.Log;

/**
 * Used to intercept and handle the responses from requests made using 
 * {@link AsyncHttpClient}. The {@link #onSuccess(String)} method is 
 * designed to be anonymously overridden with your own response handling code.
 * <p>
 * Additionally, you can override the {@link #onFailure(Throwable, String)},
 * {@link #onStart()}, and {@link #onFinish()} methods as required.
 * <p>
 * For example:
 * <p>
 * <pre>
 * AsyncHttpClient client = new AsyncHttpClient();
 * client.get("http://www.google.com", new AsyncHttpResponseHandler() {
 *     &#064;Override
 *     public void onStart() {
 *         // Initiated the request
 *     }
 *
 *     &#064;Override
 *     public void onSuccess(int statusCode, byte[] responseBody ) {
 *         // Successfully got a response
 *     }
 * 
 *     &#064;Override
 *     public void onFailure(int statusCode, byte[] responseBody, Throwable e) {
 *         // Response failed :(
 *     }
 *
 *     &#064;Override
 *     public void onFinish() {
 *         // Completed the request (either success or failure)
 *     }
 * });
 * </pre>
 */
public class AsyncHttpResponseHandler {
	private static final String TAG = "HTTP/AsyncHandler";
	
    protected static final int SUCCESS_MESSAGE = 0;
    protected static final int FAILURE_MESSAGE = 1;
    protected static final int START_MESSAGE = 2;
    protected static final int FINISH_MESSAGE = 3;
    protected static final int PROGRESS_MESSAGE = 4;
    protected static final int RETRY_MESSAGE = 5;

    protected static final int BUFFER_SIZE = 4096;

    private Handler handler;

    private boolean isCanceled;
    
    // Whether we're in development environment
    protected boolean debug = false;
    
    // Used when debugging for more informative logs
    protected String requestLine;
    
    // Previous implementation used 
    // 		WeakReference<AsyncHttpResponseHandler> mResponder;
    // to avoid leaks. However occasionally we'd lose this
    // reference and our callbacks are not triggered then.
    // Of course we cannot allow this. So until we verify 
    // leaks really occur here and then find a better solution,
    // we won't be using WeakReference here.
    static class ResponderHandler extends Handler {
        private final AsyncHttpResponseHandler mResponder;
    	
        private boolean mDebug;
        private String mRequestLine;
        
        ResponderHandler(AsyncHttpResponseHandler handler) {
            mResponder = handler;
            mDebug = false;
        }
        
        @Override
        public void handleMessage(Message msg)
        {
            if (mResponder != null) {
            	mResponder.handleMessage(msg);
            } else {
            	if(mDebug) Log.d(TAG, String.format("Would handle a message in a handler, we've it is null; %s", mRequestLine));
            }
        }
        
        void setDebug(boolean isDevelopmentEnv) {
        	mDebug = isDevelopmentEnv;
        	if(mDebug) mRequestLine = "";
        }
        
        void setRequestLine(String requestLine) {
        	mRequestLine = requestLine; 
        }
    }
    
    /**
     * Creates a new AsyncHttpResponseHandler
     */
    public AsyncHttpResponseHandler() {
        // Set up a handler to post events back to the correct thread if
        // possible
        if (Looper.myLooper() != null) {
            handler = new ResponderHandler(this);
        }
    }

    /**
     * Setting this to `true` enables verbose logging.
     * Also be sure to also use `setRequestLine(String)`
     * afterwards for more informative logs.
     * 
     * @param isDevelopmentEnv
     */
    void setDebug(boolean isDevelopmentEnv) {
    	this.debug = isDevelopmentEnv;
    	
    	if(debug) {
    		requestLine = "";
    		
    		if(handler != null) {
    			((ResponderHandler)handler).setDebug(isDevelopmentEnv);
    		}
    	}
    }
    
    void setRequestLine(String requestLine) {
    	this.requestLine = requestLine;
    	if(handler != null) {
			((ResponderHandler)handler).setRequestLine(requestLine);
		}
    }

    public void cancel() {
    	isCanceled = true;
    }
    
    //
    // Callbacks to be overridden, typically anonymously
    //

    /**
     * Fired when the request is started, override to handle in your own code
     */
    public void onStart() {}

    /**
     * Fired in all cases when the request is finished, after both success and failure, override to handle in your own code
     */
    public void onFinish() {}

    /**
     * Fired when a request returns successfully, override to handle in your own
     * code
     * 
     * @param statusCode the status code of the response
     * @param responseBody the body of the HTTP response from the server
     */
    public void onSuccess(int statusCode, byte[] responseBody) {
    }


    /**
     * Fired when a request fails to complete, override to handle in your own
     * code
     * 
     * @param statusCode the status code of the response
     * @param responseBody the response body, if any
     * @param error the underlying cause of the failure
     */
    public void onFailure(int statusCode, byte[] responseBody, Throwable error) {
    }

    /**
     * Fired when a bytes are received, override to handle in your own
     * code
     * 
     * @param current the current number of bytes loaded from the response
     * @param total the total number of bytes in the response
     */
    public void onProgress(int current, int total) {
    }

    /**
     * Fired when a retry occurs, override to handle in your own
     * code
     * 
     */
    public void onRetry() {
    }
    

    //
    // Pre-processing of messages (executes in background threadpool thread)
    //

    protected void sendSuccessMessage(int statusCode, byte[] responseBody) {
    	if(debug) Log.d(TAG, String.format("Sending success message (HTTP %d, %,d bytes) for %s", statusCode, responseBody.length, requestLine));
        sendMessage(obtainMessage(SUCCESS_MESSAGE, new Object[] { Integer.valueOf(statusCode), responseBody }));
    }

    protected void sendFailureMessage(int statusCode, byte[] responseBody, Throwable error) {
    	if(debug) Log.d(TAG, String.format("Sending failure message %d caused by %s for %s", statusCode, error.getClass().getSimpleName(), requestLine));
        sendMessage(obtainMessage(FAILURE_MESSAGE, new Object[] { Integer.valueOf(statusCode), responseBody, error }));
    }

    protected void sendStartMessage() {
    	if(debug) Log.d(TAG, String.format("Sending start message %s", requestLine));
        sendMessage(obtainMessage(START_MESSAGE, null));
    }

    protected void sendFinishMessage() {
    	if(debug) Log.d(TAG, String.format("Sending finish message  %s", requestLine));
        sendMessage(obtainMessage(FINISH_MESSAGE, null));
    }

    protected void sendProgressMessage(int current, int total) {
    	// No debug logging here because this generates waaay too much logs
        sendMessage(obtainMessage(PROGRESS_MESSAGE, new Object[] { current, total }));
    }

    protected void sendRetryMessage() {
    	if(debug) Log.d(TAG, String.format("Sending retry message  %s", requestLine));
    	sendMessage(obtainMessage(RETRY_MESSAGE, null));
    }
    
    //
    // Pre-processing of messages (in original calling thread, typically the UI thread)
    //

    protected void handleSuccessMessage(int statusCode, byte[] responseBody) {
    	if(debug) Log.d(TAG, String.format("Handling success message (HTTP %d; %,d bytes) for %s", statusCode, responseBody.length, requestLine));
        onSuccess(statusCode, responseBody);
    }

    protected void handleFailureMessage(int statusCode, byte[] responseBody, Throwable error) {
    	if(debug) Log.d(TAG, String.format("Handling failure message (HTTP %d), caused by %s, for %s", statusCode, error.getClass().getSimpleName(), requestLine));
        onFailure(statusCode, responseBody, error);
    }

    protected void handleProgressMessage(int current, int total) {
        onProgress(current, total);
    }

    protected void handleRetryMessage() {
    	if(debug) Log.d(TAG, String.format("Handling retry message for %s", requestLine));
    	onRetry();
    }
    
    // Methods which emulate android's Handler and Message methods
    protected void handleMessage(Message msg) {
        Object[] response;

        switch (msg.what) {
        case SUCCESS_MESSAGE:
            response = (Object[]) msg.obj;
            handleSuccessMessage(((Integer) response[0]).intValue(), (byte[]) response[1]);
            break;
        case FAILURE_MESSAGE:
            response = (Object[]) msg.obj;
            handleFailureMessage(((Integer) response[0]).intValue(), (byte[]) response[1], (Throwable) response[2]);
            break;
        case START_MESSAGE:
            onStart();
            break;
        case FINISH_MESSAGE:
            onFinish();
            break;
        case PROGRESS_MESSAGE:
            response = (Object[]) msg.obj;
            onProgress(((Integer) response[0]).intValue(), ((Integer) response[1]).intValue());
            break;
        case RETRY_MESSAGE:
            onRetry();
          break;
        }
    }

    protected void sendMessage(Message msg) {
        // do not send messages if request has been cancelled
        if (!Thread.currentThread().isInterrupted() && !isCanceled) {
            if (handler != null) {
            	if(debug) Log.d(TAG, String.format("Sending message to handler for %s", requestLine));
                handler.sendMessage(msg);
            } else {
            	if(debug) Log.d(TAG, String.format("Sending message without handler for %s", requestLine));
                handleMessage(msg);
            }
        } else {
        	if(debug) Log.d(TAG, String.format("Not sending message %s because thread was interrupted or request was canceled (%b) %s", msg.toString(), isCanceled, requestLine));
        }
    }

    protected Message obtainMessage(int responseMessage, Object response) {
        Message msg = null;
        if(handler != null){
            msg = this.handler.obtainMessage(responseMessage, response);
        }else{
            msg = Message.obtain();
            msg.what = responseMessage;
            msg.obj = response;
        }
        return msg;
    }

    byte[] getResponseData(HttpEntity entity) throws IOException {
        byte[] responseBody = null;
        if (entity != null) {
        	long contentLength = entity.getContentLength();
            if (contentLength > Integer.MAX_VALUE) {
            	entity.consumeContent();
                throw new IllegalArgumentException("HTTP entity too large to be buffered in memory");
            }
            
            // If server didn't report content length (via Content-Length header),
            // don't send progress and read whole response at once.
            // 
            // On the other hand, if content length is known, read it chunk by chunk
            // reporting progress on the way.
            if(contentLength < 0) {
            	if(debug) Log.d(TAG, String.format("Consuming response - unknown size for %s", requestLine));
            	
            	responseBody = EntityUtils.toByteArray(entity);
            } else {
                InputStream instream = entity.getContent();
                if (instream != null) {
                	
                	if(debug) Log.d(TAG, String.format("Consuming response - size is %,d bytes for %s", contentLength, requestLine));
                	
                    try{
                        ByteArrayBuffer buffer = new ByteArrayBuffer((int) contentLength);
                        
                        try {
                            byte[] tmp = new byte[BUFFER_SIZE];
                            int l, count = 0;
                            // do not send messages if request has been cancelled
                            while ((l = instream.read(tmp)) != -1 && !Thread.currentThread().isInterrupted()) {
                                count += l;
                                buffer.append(tmp, 0, l);
                                sendProgressMessage(count, (int) contentLength);
                            }
                        } finally {
                            instream.close();
                        }
                        responseBody = buffer.buffer();
                    } catch( OutOfMemoryError e ) {
                        System.gc();
                        entity.consumeContent();
                        throw new IOException("File too large to fit into available memory");
                    }
                }
            }
            
            // We need to fully consume (or rather finalise reading of)
            // entity so that connection can be given back to
            // connection pool.
            // 
            // Otherwise, connections are help up, and subsequent requests
            // fail with `ConnectionPoolTimeoutException`.
            //
            // Following answer suggested this approach:
            // http://stackoverflow.com/a/4621737/74174
            entity.consumeContent();
            
            if(debug) Log.d(TAG, String.format("Consumed response, total %,d bytes for %s", responseBody.length, requestLine));
            
        } else {
        	if(debug) Log.d(TAG, String.format("Consuming response but entity is `null` for %s", requestLine));
        }
        
        return (responseBody);
    }
    
    // Interface to AsyncHttpRequest
    void sendResponseMessage(HttpResponse response) throws IOException {
        // do not process if request has been cancelled
        if (!Thread.currentThread().isInterrupted() && !isCanceled) {
            StatusLine status = response.getStatusLine();
            byte[] responseBody = null;
            responseBody = getResponseData(response.getEntity());
            
            if(debug) Log.d(TAG, String.format("Sending response message of total %,d bytes for %s", responseBody.length, requestLine));
            
            if (status.getStatusCode() >= 300) {
                sendFailureMessage(status.getStatusCode(), responseBody, new HttpResponseException(status.getStatusCode(), status.getReasonPhrase()));
            } else {
                sendSuccessMessage(status.getStatusCode(), responseBody);
            }
        } else {
        	if(debug) Log.d(TAG, String.format("NOT sending response message as thread was interrunpted or request canceled (%b) for %s", isCanceled, requestLine));
        }
    }
}