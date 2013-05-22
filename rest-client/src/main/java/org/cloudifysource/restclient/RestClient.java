/*******************************************************************************
 * Copyright (c) 2013 GigaSpaces Technologies Ltd. All rights reserved
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package org.cloudifysource.restclient;


import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.http.HttpVersion;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.impl.client.AbstractHttpClient;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.params.HttpProtocolParams;
import org.apache.http.protocol.HTTP;
import org.cloudifysource.dsl.internal.CloudifyConstants;
import org.cloudifysource.dsl.internal.CloudifyMessageKeys;
import org.cloudifysource.dsl.rest.request.InstallServiceRequest;
import org.cloudifysource.dsl.rest.response.InstallServiceResponse;
import org.cloudifysource.dsl.rest.response.Response;
import org.cloudifysource.dsl.rest.response.ServiceDeploymentEvents;
import org.cloudifysource.dsl.rest.response.UploadResponse;
import org.cloudifysource.restclient.exceptions.RestClientException;
import org.codehaus.jackson.type.TypeReference;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.security.KeyStore;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

/**
 * 
 * @author yael
 *
 */
public class RestClient {

	private static final Logger logger = Logger.getLogger(RestClient.class.getName());

    private static final String FAILED_CREATING_CLIENT = "failed_creating_client";

	private RestClientExecutor executor;
	
	private static final String UPLOAD_CONTROLLER_URL = "/upload/";
	private static final String DEPLOYMENT_CONTROLLER_URL = "/deployments/";
	private String versionedDeploymentControllerUrl;
	private String versionedUploadControllerUrl;


	private static final String HTTPS = "https";


	/**
	 * Returns a HTTP client configured to use SSL.
	 * @param url 
	 * 
	 * @return HTTP client configured to use SSL
	 * @throws org.cloudifysource.restclient.exceptions.RestClientException
	 *             Reporting different failures while creating the HTTP client
	 */
	private DefaultHttpClient getSSLHttpClient(final URL url) throws RestClientException {
		try {
			final KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
			// TODO : support self-signed certs if configured by user upon
			// "connect"
			trustStore.load(null, null);

			final SSLSocketFactory sf = new RestSSLSocketFactory(trustStore);
			sf.setHostnameVerifier(SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);

			final HttpParams params = new BasicHttpParams();
			HttpProtocolParams.setVersion(params, HttpVersion.HTTP_1_1);
			HttpProtocolParams.setContentCharset(params, HTTP.UTF_8);

			final SchemeRegistry registry = new SchemeRegistry();
			registry.register(new Scheme(HTTPS, sf, url.getPort()));

			final ClientConnectionManager ccm = new ThreadSafeClientConnManager(params, registry);

			return new DefaultHttpClient(ccm, params);
		} catch (final Exception e) {
			throw new RestClientException(null,
                                          "Failed creating http client",
                                          ExceptionUtils.getFullStackTrace(e));
		}
	}

	/**
	 * Sets username and password for the HTTP client.
	 * 
	 * @param username
	 *            Username for the HTTP client.
	 * @param password
	 *            Password for the HTTP client.
	 * @param httpClient 
	 */
	private void setCredentials(final String username, final String password, final AbstractHttpClient httpClient) {
		if (StringUtils.notEmpty(username) && StringUtils.notEmpty(password)) {
			httpClient.getCredentialsProvider().setCredentials(new AuthScope(AuthScope.ANY),
					new UsernamePasswordCredentials(username, password));
		}
	}

	/**
	 * 
	 * @param applicationName
	 * 			The name of the application.
	 * @param serviceName
	 * 			The name of the service to install.
	 * @param request
	 * 			The install service request.
	 * @return The install service response.
	 * @throws RestClientException .
	 * @throws TimeoutException .
	 * @throws IOException .
	 */
	public InstallServiceResponse installService(final String applicationName, 
			final String serviceName, final InstallServiceRequest request) 
					throws RestClientException , TimeoutException , IOException {
		final String installServiceUrl = versionedDeploymentControllerUrl + applicationName + "/services/" + serviceName;
		return executor.postObject(
                        installServiceUrl,
						request, 
						new TypeReference<Response<InstallServiceResponse>>() { }
						);
	}

    public ServiceDeploymentEvents getServiceDeploymentEvents(final String deploymentId,
                                                              final int from,
                                                              final int to) throws IOException, RestClientException {
        final String getServiceDeploymentEventsUrl = versionedDeploymentControllerUrl + "/" + deploymentId + "?from=" + from + "&to=" + to;
        return executor.get(
                getServiceDeploymentEventsUrl,
                new TypeReference<Response<ServiceDeploymentEvents>>() {});
    }

	/**
	 * Uploads a file to the repository.
	 * @param fileName
	 * 		The name of the file to upload.
	 * @param file
	 * 		The file to upload.
	 * @return upload response.
	 * @throws RestClientException .
	 */
	public UploadResponse upload(final String fileName, final File file) 
			throws RestClientException {
		if (file == null) {
			throw new RestClientException("upload_file_missing", 
					"the required file parameter (the file to upload) is missing", null);
		}
		String finalFileName = fileName == null ? file.getName() : fileName;
		final String uploadUrl = 
				versionedUploadControllerUrl + finalFileName;
		UploadResponse response = executor.postFile(
				uploadUrl, 
				file, 
				CloudifyConstants.UPLOAD_FILE_PARAM_NAME, 
				new TypeReference<Response<UploadResponse>>() {
		});
		return response;
	}

    public void connect(final URL url,
                        final String username,
                        final String password,
                        final String apiVersion) throws IOException, RestClientException, RestClientException {

        this.executor = createExecutor(url, username, password, apiVersion);

        executor.get(versionedDeploymentControllerUrl + "/testrest", new TypeReference<Response<Void>>() {});
    }

    private RestClientExecutor createExecutor(final URL url,
                                              final String username,
                                              final String password,
                                              final String apiVersion) throws RestClientException {
        DefaultHttpClient httpClient;
        if (HTTPS.equals(url.getProtocol())) {
            httpClient = getSSLHttpClient(url);
        } else {
            httpClient = new DefaultHttpClient();
        }
        final HttpParams httpParams = httpClient.getParams();
        HttpConnectionParams.setConnectionTimeout(httpParams, CloudifyConstants.DEFAULT_HTTP_CONNECTION_TIMEOUT);
        HttpConnectionParams.setSoTimeout(httpParams, CloudifyConstants.DEFAULT_HTTP_READ_TIMEOUT);

        setCredentials(username, password, httpClient);
        versionedDeploymentControllerUrl = apiVersion + DEPLOYMENT_CONTROLLER_URL;
        versionedUploadControllerUrl = apiVersion + UPLOAD_CONTROLLER_URL;
        return new RestClientExecutor(httpClient, url);
    }
}
