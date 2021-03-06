/*
 *     Copyright 2015 IBM Corp.
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 */

package com.ibm.mobilefirstplatform.clientsdk.android.security.mca.internal;

import android.content.Context;

import com.ibm.mobilefirstplatform.clientsdk.android.core.api.BMSClient;
import com.ibm.mobilefirstplatform.clientsdk.android.core.api.Request;
import com.ibm.mobilefirstplatform.clientsdk.android.core.api.Response;
import com.ibm.mobilefirstplatform.clientsdk.android.core.api.ResponseListener;
import com.ibm.mobilefirstplatform.clientsdk.android.core.internal.ResponseImpl;
import com.ibm.mobilefirstplatform.clientsdk.android.logger.api.Logger;
import com.ibm.mobilefirstplatform.clientsdk.android.security.api.AuthorizationManager;
import com.ibm.mobilefirstplatform.clientsdk.android.security.mca.api.MCAAuthorizationManager;
import com.ibm.mobilefirstplatform.clientsdk.android.security.mca.internal.challengehandlers.ChallengeHandler;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Created by vitalym on 7/16/15.
 */

/**
 * AuthorizationRequestAgent builds and sends requests to authorization server. It also handles
 * authorization challenges and re-sends the requests as necessary.
 */
public class AuthorizationRequestManager implements ResponseListener {
    private static Logger logger = Logger.getLogger(Logger.INTERNAL_PREFIX + "AuthorizationRequestAgent");
    public static String overrideServerHost = null;
    /**
     * Parts of the path to authorization endpoint.
     */
    private final static String AUTH_SERVER_NAME = "imf-authserver";
    private final static String AUTH_PATH = "authorization/v1/apps/";

    /**
     * The name of "result" parameter returned from authorization endpoint.
     */
    private final static String WL_RESULT = "wl_result";

    /**
     * Name of rewrite domain header. This header is added to authorization requests.
     */
    private final static String REWRITE_DOMAIN_HEADER_NAME = "X-REWRITE-DOMAIN";

    /**
     * Name of location header.
     */
    private final static String LOCATION_HEADER_NAME = "Location";

    /**
     * Name of the standard "www-authenticate" header.
     */
    private final static String AUTHENTICATE_HEADER_NAME = "WWW-Authenticate";

    /**
     * Name of "www-authenticate" header value.
     */
    private final static String AUTHENTICATE_HEADER_VALUE = "WL-Composite-Challenge";

    /**
     * Names of JSON values returned from the server.
     */
    private final static String AUTH_FAILURE_VALUE_NAME = "WL-Authentication-Failure";
    private final static String AUTH_SUCCESS_VALUE_NAME = "WL-Authentication-Success";
    private final static String CHALLENGES_VALUE_NAME = "challenges";

    /**
     * requestPath and requestOptions are cached to re-send a request after all challenges have been handled.
     */
    private String requestPath;
    private RequestOptions requestOptions;

    /**
     * Response listener specified by request sender.
     */
    private ResponseListener listener;

    /**
     * Contains challenge answers. Each answer is mapped to a realm.
     */
    private JSONObject answers;

    /**
     * Context is provided by the caller during initialization and passed to challenge handlers later.
     */
    private Context context;

    /**
     * The request options are specified by the caller and cached for subsequent requests.
     */
    static public class RequestOptions {
        public RequestOptions() {
            requestMethod = Request.GET;
        }

        public String requestMethod;
        public int timeout;

        public HashMap<String, String> headers;
        public HashMap<String, String> parameters;
    }

    /**
     * Initializes the request manager.
     *
     * @param context  Context to be cached and passed to challenge handlers later.
     * @param listener Response listener. Called when an authorization response has been processed.
     */
    public void initialize(Context context, ResponseListener listener) {
        this.context = context;
        this.listener = (listener != null) ? listener : new ResponseListener() {
            final String message = "ResponseListener is not specified. Defaulting to empty listener.";

            @Override
            public void onSuccess(Response response) {
                logger.debug(message);
            }

            @Override
            public void onFailure(Response response, Throwable t, JSONObject extendedInfo) {
                logger.debug(message);
            }
        };

        logger.debug("AuthorizationRequestAgent is initialized.");
    }

    /**
     * Assembles the request path from root and path to authorization endpoint and sends the request.
     *
     * @param path    Path to authorization endpoint
     * @param options BaseRequest options
     * @throws IOException
     * @throws JSONException
     */
    public void sendRequest(String path, RequestOptions options) throws IOException, JSONException {
        String rootUrl;

        if (path == null) {
            throw new IllegalArgumentException("'path' parameter can't be null.");
        }

        if (path.indexOf(BMSClient.HTTP_SCHEME) == 0 && path.contains(":")) {
            // request using full path, split the URL to root and path
            URL url = new URL(path);
            path = url.getPath();
            rootUrl = url.toString().replace(path, "");
        } else {
            // "path" is a relative

			String serverHost = BMSClient.getInstance().getDefaultProtocol()
							+ "://"
							+ AUTH_SERVER_NAME
							+ BMSClient.getInstance().getBluemixRegionSuffix();

			if (overrideServerHost!=null)
				serverHost = overrideServerHost;

            rootUrl = serverHost
                    + "/"
                    + AUTH_SERVER_NAME
                    + "/"
                    + AUTH_PATH
                    + BMSClient.getInstance().getBluemixAppGUID();
        }

        sendRequestInternal(rootUrl, path, options);
    }

    /**
     * Re-sends an authorization request after all challenges have been handled.
     *
     * @throws IOException
     * @throws JSONException
     */
    public void resendRequest() throws IOException, JSONException {
        sendRequest(requestPath, requestOptions);
    }

    /**
     * Builds an authorization request and sends it. It also caches the request url and request options in
     * order to be able to re-send the request when authorization challenges have been handled.
     *
     * @param rootUrl Root of authorization server.
     * @param path    Path to authorization endpoint.
     * @param options BaseRequest options.
     * @throws IOException
     * @throws JSONException
     */
    private void sendRequestInternal(String rootUrl, String path, RequestOptions options) throws IOException, JSONException {
        logger.debug("Sending request to root: " + rootUrl + " with path: " + path);

        // create default options object with GET request method.
        if (options == null) {
            options = new RequestOptions();
        }

        // used to resend request
        this.requestPath = Utils.concatenateUrls(rootUrl, path);
        this.requestOptions = options;

        AuthorizationRequest request = new AuthorizationRequest(this.requestPath, options.requestMethod);

        if (options.timeout != 0) {
            request.setTimeout(options.timeout);
        } else {
            request.setTimeout(BMSClient.getInstance().getDefaultTimeout());
        }

        if (options.headers != null) {
            for (Map.Entry<String, String> entry : options.headers.entrySet()) {
                request.addHeader(entry.getKey(), entry.getValue());
            }
        }

        if (answers != null) {
            // 0 means no spaces in the generated string
            String answer = answers.toString(0);

            String authorizationHeaderValue = String.format("Bearer %s", answer.replace("\n", ""));
            request.addHeader("Authorization", authorizationHeaderValue);
            logger.debug("Added authorization header to request: " + authorizationHeaderValue);
        }

        if (Request.GET.equalsIgnoreCase(options.requestMethod)) {
            request.setQueryParameters(options.parameters);
            request.send(this);
        } else {
            request.send(options.parameters, this);
        }
    }

    /**
     * Initializes the collection of expected challenge answers.
     *
     * @param realms List of realms
     */
    private void setExpectedAnswers(ArrayList<String> realms) {
        if (answers == null) {
            return;
        }

        for (String realm : realms) {
            try {
                answers.put(realm, "");
            } catch (JSONException t) {
                logger.error("setExpectedAnswers failed with exception: " + t.getLocalizedMessage(), t);
            }
        }
    }

    /**
     * Removes an expected challenge answer from collection.
     *
     * @param realm Realm of the answer to remove.
     */
    public void removeExpectedAnswer(String realm) {
        if (answers != null) {
            answers.remove(realm);
        }

        try {
            if (isAnswersFilled()) {
                resendRequest();
            }
        } catch (Throwable t) {
            logger.error("removeExpectedAnswer failed with exception: " + t.getLocalizedMessage(), t);
        }
    }

    /**
     * Adds an expected challenge answer to collection of answers.
     *
     * @param answer Answer to add.
     * @param realm  Authentication realm for the answer.
     */
    public void submitAnswer(JSONObject answer, String realm) {
        if (answers == null) {
            answers = new JSONObject();
        }

        try {
            answers.put(realm, answer);
            if (isAnswersFilled()) {
                resendRequest();
            }
        } catch (Throwable t) {
            logger.error("submitAnswer failed with exception: " + t.getLocalizedMessage(), t);
        }
    }

    /**
     * Verifies whether all expected challenges have been answered, or not.
     *
     * @return <code>true</code> if all answers have been submitted, otherwise <code>false</code>.
     * @throws JSONException
     */
    public boolean isAnswersFilled() throws JSONException {
        if (answers == null) {
            return true;
        }

        Iterator<String> it = answers.keys();
        while (it.hasNext()) {
            String key = it.next();
            Object value = answers.get(key);

            if ((value instanceof String) && value.equals("")) {
                return false;
            }
        }

        return true;
    }

    /**
     * Processes redirect response from authorization endpoint.
     *
     * @param response Response from the server.
     */
    private void processRedirectResponse(Response response) throws RuntimeException, JSONException, MalformedURLException {
        // a valid redirect response must contain the Location header.
        ResponseImpl responseImpl = (ResponseImpl)response;
        List<String> locationHeaders = responseImpl.getHeader(LOCATION_HEADER_NAME);

        String location = (locationHeaders != null && locationHeaders.size() > 0) ? locationHeaders.get(0) : null;

        if (location == null) {
            throw new RuntimeException("Redirect response does not contain 'Location' header.");
        }

        // the redirect location url should contain "wl_result" value in query parameters.
        URL url = new URL(location);
        String query = url.getQuery();

        if (query.contains(WL_RESULT)) {
            String result = Utils.getParameterValueFromQuery(query, WL_RESULT);
            JSONObject jsonResult = new JSONObject(result);

            // process failures if any
            JSONObject jsonFailures = jsonResult.optJSONObject(AUTH_FAILURE_VALUE_NAME);

            if (jsonFailures != null) {
                processFailures(jsonFailures);
                listener.onFailure(response, null, null);
                return;
            }

            // process successes if any
            JSONObject jsonSuccesses = jsonResult.optJSONObject(AUTH_SUCCESS_VALUE_NAME);

            if (jsonSuccesses != null) {
                processSuccesses(jsonSuccesses);
            }
        }

        // the rest is handles by the caller
        listener.onSuccess(response);
    }

    /**
     * Process a response from the server.
     *
     * @param response Server response.
     */
    private void processResponse(Response response) {
        // at this point a server response should contain a secure JSON with challenges
        JSONObject jsonResponse = Utils.extractSecureJson(response);
        JSONObject jsonChallenges = (jsonResponse == null) ? null : jsonResponse.optJSONObject(CHALLENGES_VALUE_NAME);

        if (jsonChallenges != null) {
            startHandleChallenges(jsonChallenges, response);
        } else {
            listener.onSuccess(response);
        }
    }

    /**
     * Handles authentication challenges.
     *
     * @param jsonChallenges Collection of challenges.
     * @param response       Server response.
     */
    private void startHandleChallenges(JSONObject jsonChallenges, Response response) {
        ArrayList<String> challenges = getRealmsFromJson(jsonChallenges);

        MCAAuthorizationManager authManager = (MCAAuthorizationManager) BMSClient.getInstance().getAuthorizationManager();

        if (isAuthorizationRequired(response)) {
            setExpectedAnswers(challenges);
        }

        for (String realm : challenges) {
            ChallengeHandler handler = authManager.getChallengeHandler(realm);
            if (handler != null) {
                JSONObject challenge = jsonChallenges.optJSONObject(realm);
                handler.handleChallenge(this, challenge, context);
            } else {
                throw new RuntimeException("Challenge handler for realm is not found: " + realm);
            }
        }
    }

    /**
     * Checks server response for MFP 401 error. This kind of response should contain MFP authentication challenges.
     *
     * @param response Server response.
     * @return <code>true</code> if the server response contains 401 status code along with MFP challenges.
     */
    private boolean isAuthorizationRequired(Response response) {
        if (response != null && response.getStatus() == 401) {
            ResponseImpl responseImpl = (ResponseImpl)response;
            String challengesHeader = responseImpl.getFirstHeader(AUTHENTICATE_HEADER_NAME);

            if (AUTHENTICATE_HEADER_VALUE.equalsIgnoreCase(challengesHeader)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Processes authentication failures.
     *
     * @param jsonFailures Collection of authentication failures.
     */
    private void processFailures(JSONObject jsonFailures) {
        if (jsonFailures == null) {
            return;
        }
        MCAAuthorizationManager authManager = (MCAAuthorizationManager) BMSClient.getInstance().getAuthorizationManager();

        ArrayList<String> challenges = getRealmsFromJson(jsonFailures);
        for (String realm : challenges) {
            ChallengeHandler handler = authManager.getChallengeHandler(realm);
            if (handler != null) {
                JSONObject challenge = jsonFailures.optJSONObject(realm);
                handler.handleFailure(context, challenge);
            } else {
                logger.error("Challenge handler for realm is not found: " + realm);
            }
        }
    }

    /**
     * Processes authentication successes.
     *
     * @param jsonSuccesses Collection of authentication successes.
     */
    private void processSuccesses(JSONObject jsonSuccesses) {
        if (jsonSuccesses == null) {
            return;
        }

        MCAAuthorizationManager authManager = (MCAAuthorizationManager) BMSClient.getInstance().getAuthorizationManager();
        ArrayList<String> challenges = getRealmsFromJson(jsonSuccesses);
        for (String realm : challenges) {
            ChallengeHandler handler = authManager.getChallengeHandler(realm);
            if (handler != null) {
                JSONObject challenge = jsonSuccesses.optJSONObject(realm);
                handler.handleSuccess(context, challenge);
            } else {
                logger.error("Challenge handler for realm is not found: " + realm);
            }
        }
    }

    /**
     * Called when a request to authorization server failed.
     *
     * @param info Extended information about the failure.
     */
    public void requestFailed(JSONObject info) {
        logger.error("BaseRequest failed with info: " + (info == null ? "info is null" : info.toString()));
        listener.onFailure(null, null, info);
    }

    /**
     * Iterates a JSON object containing authorization challenges and builds a list of reals.
     *
     * @param jsonChallenges Collection of challenges.
     * @return Array with realms.
     */
    private ArrayList<String> getRealmsFromJson(JSONObject jsonChallenges) {
        Iterator<String> challengesIterator = jsonChallenges.keys();
        ArrayList<String> challenges = new ArrayList<>();

        while (challengesIterator.hasNext()) {
            challenges.add(challengesIterator.next());
        }

        return challenges;
    }

    /**
     * Called when request succeeds.
     *
     * @param response the server response
     */
    @Override
    public void onSuccess(Response response) {
        processResponseWrapper(response, false);
    }

    /**
     * Called when request fails.
     *
     * @param response Contains detail regarding why the request failed
     * @param t        Exception that could have caused the request to fail. Null if no Exception thrown.
     */
    @Override
    public void onFailure(Response response, Throwable t, JSONObject extendedInfo) {
        if (isAuthorizationRequired(response)) {
            processResponseWrapper(response, true);
        } else {
            listener.onFailure(response, t, extendedInfo);
        }
    }

    /**
     * Called from onSuccess and onFailure. Handles all possible exceptions and notifies the listener
     * if an exception occurs.
     *
     * @param response  server response
     * @param isFailure specifies whether this method is called from onSuccess (false) or onFailure (true).
     */
    private void processResponseWrapper(Response response, boolean isFailure) {
        try {
            ResponseImpl responseImpl = (ResponseImpl)response;
            if (isFailure || !responseImpl.isRedirect()) {
                processResponse(response);
            } else {
                processRedirectResponse(response);
            }
        } catch (Throwable t) {
            logger.error("processResponseWrapper caught exception: " + t.getLocalizedMessage());
            listener.onFailure(response, t, null);
        }
    }
}
