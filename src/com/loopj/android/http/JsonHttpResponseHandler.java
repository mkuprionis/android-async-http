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

import java.io.UnsupportedEncodingException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import android.os.Message;
import android.util.Log;

/**
 * Used to intercept and handle the responses from requests made using
 * {@link AsyncHttpClient}, with automatic parsing into a {@link JSONObject}
 * or {@link JSONArray}.
 * <p>
 * This class is designed to be passed to get, post, put and delete requests
 * with the {@link #onSuccess(JSONObject)} or {@link #onSuccess(JSONArray)}
 * methods anonymously overridden.
 * <p>
 * Additionally, you can override the other event methods from the
 * parent class.
 */
public class JsonHttpResponseHandler extends AsyncHttpResponseHandler {
    protected static final int SUCCESS_JSON_MESSAGE = 100;
    protected static final int FAILURE_JSON_MESSAGE = 101;

    private static final String TAG = "HTTP/JsonHandler";
    
    //
    // Callbacks to be overridden, typically anonymously
    //

    /**
     * Fired when a request returns successfully and contains a json object
     * at the base of the response string. Override to handle in your
     * own code.
     * @param response the parsed json object found in the server response (if any)
     */
    public void onSuccess(JSONObject response) {}


    /**
     * Fired when a request returns successfully and contains a json array
     * at the base of the response string. Override to handle in your
     * own code.
     * @param response the parsed json array found in the server response (if any)
     */
    public void onSuccess(JSONArray response) {}

    /**
     * Fired when a request returns successfully and contains a json object
     * at the base of the response string. Override to handle in your
     * own code.
     * @param statusCode the status code of the response
     * @param response the parsed json object found in the server response (if any)
     */
    public void onSuccess(int statusCode, JSONObject response) {
        onSuccess(response);
    }


    /**
     * Fired when a request returns successfully and contains a json array
     * at the base of the response string. Override to handle in your
     * own code.
     * @param statusCode the status code of the response
     * @param response the parsed json array found in the server response (if any)
     */
    public void onSuccess(int statusCode, JSONArray response) {
        onSuccess(response);
    }

    
    /**
     * Fired when a request fails and contains a json object
     * at the base of the response string. Override to handle in your
     * own code.
     * <br/>
     * <strong>NOTE:</strong> remember to override the superclass {@link AsyncHttpResponseHandler#onFailure(int, byte[], Throwable) onFailure}
     * method to cover all failure scenarios
     * @param statusCode the status code of the response
     * @param errorResponse the parsed json object found in the server response (if any)
     * @param error the underlying cause of the failure
     */
    public void onFailure(int statusCode, JSONObject errorResponse, Throwable error) {}
    
    /**
     * Fired when a request fails and contains a json array
     * at the base of the response string. Override to handle in your
     * own code.
     * <br/>
     * <strong>NOTE:</strong> remember to override the superclass {@link AsyncHttpResponseHandler#onFailure(int, byte[], Throwable) onFailure}
     * method to cover all failure scenarios
     * @param statusCode the status code of the response
     * @param errorResponse the parsed json array found in the server response (if any)
     * @param error the underlying cause of the failure
     */
    public void onFailure(int statusCode, JSONArray errorResponse, Throwable error) {}


    //
    // Pre-processing of messages (executes in background threadpool thread)
    //

    @Override
    protected void sendSuccessMessage(int statusCode, byte[] responseBody) {
        try {
            Object jsonResponse = parseResponse(responseBody);
            if(debug) Log.v(TAG, String.format("Sending success message (HTTP %d, %,d bytes) for %s", statusCode, responseBody.length, requestLine));
            sendMessage(obtainMessage(SUCCESS_JSON_MESSAGE, new Object[] { Integer.valueOf(statusCode), jsonResponse }));
        } catch (JSONException e) {
        	if(debug) Log.v(TAG, String.format("Sending failure message %d caused by %s for %s", statusCode, e.getClass().getSimpleName(), requestLine));
            sendMessage(obtainMessage(FAILURE_MESSAGE, new Object[] {Integer.valueOf(0), responseBody, e}));
        }
    }

    @Override
    protected void sendFailureMessage(int statusCode, byte[] responseBody, Throwable error) {
    	if(debug) Log.v(TAG, String.format("Sending failure message %d caused by %s for %s", statusCode, error.getClass().getSimpleName(), requestLine));
    	
        try {
            if (responseBody != null) {
                Object jsonResponse = parseResponse(responseBody);
                sendMessage(obtainMessage(FAILURE_JSON_MESSAGE, new Object[] {Integer.valueOf(statusCode), jsonResponse, error}));
            } else {
                sendMessage(obtainMessage(FAILURE_JSON_MESSAGE, new Object[] {Integer.valueOf(statusCode), (JSONObject)null, error}));
            }
        } catch (JSONException ex) {
            sendMessage(obtainMessage(FAILURE_MESSAGE, new Object[] {Integer.valueOf(0), responseBody, error}));
        }
    }

    //
    // Pre-processing of messages (in original calling thread, typically the UI thread)
    //

    @Override
    protected void handleMessage(Message msg) {
        Object[] response;
        switch(msg.what){
            case SUCCESS_JSON_MESSAGE:
                response = (Object[]) msg.obj;
                handleSuccessJsonMessage(((Integer) response[0]).intValue(), response[1]);
                break;
            case FAILURE_JSON_MESSAGE:
                response = (Object[]) msg.obj;
                handleFailureJsonMessage(((Integer) response[0]).intValue(), response[1], (Throwable) response[2]);
                break;
            default:
                super.handleMessage(msg);
        }
    }

    protected void handleSuccessJsonMessage(int statusCode, Object jsonResponse) {
    	if(debug) Log.v(TAG, String.format("Handling SuccessJsonMessage (HTTP %d)for %s", statusCode, requestLine));
    	
        if(jsonResponse instanceof JSONObject) {
            onSuccess(statusCode, (JSONObject)jsonResponse);
        } else if(jsonResponse instanceof JSONArray) {
            onSuccess(statusCode, (JSONArray)jsonResponse);
        } else {
            onFailure(statusCode, (JSONObject)null, new JSONException("Unexpected type " + jsonResponse.getClass().getName()));
        }
    }

    protected void handleFailureJsonMessage(int statusCode, Object jsonResponse, Throwable error) {
    	if(debug) Log.v(TAG, String.format("Handling FailureJsonMessage %d caused by %s for %s", statusCode, error.getClass().getSimpleName(), requestLine));
    	
        if (jsonResponse instanceof JSONObject) {
            onFailure(statusCode, (JSONObject) jsonResponse, error);
        } else if (jsonResponse instanceof JSONArray) {
            onFailure(statusCode, (JSONArray) jsonResponse, error);
        } else {
            onFailure(0, (JSONObject)null, error);
        }
    }
    
    protected Object parseResponse(byte[] responseBody) throws JSONException {
    	if(debug) Log.v(TAG, String.format("Parsing response for %s", requestLine));
    	
        Object result = null;
        String responseBodyText = null;
        try {
            responseBodyText = new String(responseBody, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new JSONException("Unable to convert response to UTF-8 string");
        }
        
        // trim the string to prevent start with blank, and test if the string
        // is valid JSON, because the parser don't do this :(. If Json is not
        // valid this will return null
        responseBodyText = responseBodyText.trim();
        if (responseBodyText.startsWith("{") || responseBodyText.startsWith("[")) {
            result = new JSONTokener(responseBodyText).nextValue();
        }
        if (result == null) {
            result = responseBodyText;
        }
        return result;
    }

}
