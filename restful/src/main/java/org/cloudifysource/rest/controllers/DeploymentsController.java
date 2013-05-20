/*******************************************************************************
 * Copyright (c) 2013 GigaSpaces Technologies Ltd. All rights reserved
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *******************************************************************************/
package org.cloudifysource.rest.controllers;


import net.jini.core.discovery.LookupLocator;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.cloudifysource.dsl.ComputeDetails;
import org.cloudifysource.dsl.Service;
import org.cloudifysource.dsl.cloud.Cloud;
import org.cloudifysource.dsl.internal.*;
import org.cloudifysource.dsl.internal.packaging.FileAppender;
import org.cloudifysource.dsl.internal.packaging.Packager;
import org.cloudifysource.dsl.rest.request.*;
import org.cloudifysource.dsl.rest.response.*;
import org.cloudifysource.dsl.utils.ServiceUtils;
import org.cloudifysource.rest.RestConfiguration;
import org.cloudifysource.rest.deploy.DeploymentConfig;
import org.cloudifysource.rest.deploy.ElasticDeploymentCreationException;
import org.cloudifysource.rest.deploy.ElasticProcessingUnitDeploymentFactory;
import org.cloudifysource.rest.deploy.ElasticProcessingUnitDeploymentFactoryImpl;
import org.cloudifysource.rest.events.cache.EventsCache;
import org.cloudifysource.rest.events.cache.EventsCacheKey;
import org.cloudifysource.rest.events.cache.EventsCacheValue;
import org.cloudifysource.rest.events.cache.EventsUtils;
import org.cloudifysource.rest.interceptors.ApiVersionValidationAndRestResponseBuilderInterceptor;
import org.cloudifysource.rest.repo.UploadRepo;
import org.cloudifysource.rest.util.IsolationUtils;
import org.cloudifysource.rest.util.LifecycleEventsContainer;
import org.cloudifysource.rest.util.RestPollingRunnable;
import org.cloudifysource.rest.validators.InstallServiceValidationContext;
import org.cloudifysource.rest.validators.InstallServiceValidator;
import org.cloudifysource.security.CustomPermissionEvaluator;
import org.openspaces.admin.Admin;
import org.openspaces.admin.AdminException;
import org.openspaces.admin.application.Application;
import org.openspaces.admin.gsm.GridServiceManager;
import org.openspaces.admin.pu.ProcessingUnit;
import org.openspaces.admin.pu.ProcessingUnitInstance;
import org.openspaces.admin.pu.elastic.ElasticStatefulProcessingUnitDeployment;
import org.openspaces.admin.pu.elastic.ElasticStatelessProcessingUnitDeployment;
import org.openspaces.admin.pu.elastic.topology.ElasticDeploymentTopology;
import org.openspaces.admin.space.ElasticSpaceDeployment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This controller is responsible for retrieving information about deployments. It is also the entry point for deploying
 * services and application. <br>
 * <br>
 * The response body will always return in a JSON representation of the {@link Response} Object. <br>
 * A controller method may return the {@link Response} Object directly. in this case this return value will be used as
 * the response body. Otherwise, an implicit wrapping will occur. the return value will be inserted into
 * {@code Response#setResponse(Object)}. other fields of the {@link Response} object will be filled with default 
 * values. <br>
 * <h1>Important</h1> {@code @ResponseBody} annotations are not permitted. <br>
 * <br>
 * <h1>Possible return values</h1> 200 - OK<br>
 * 400 - controller throws an exception<br>
 * 500 - Unexpected exception<br>
 * <br>
 * 
 * @see {@link ApiVersionValidationAndRestResponseBuilderInterceptor}
 * @author elip , ahmadm
 * @since 2.5.0
 * 
 */

@Controller
@RequestMapping(value = "/{version}/deployments")
public class DeploymentsController extends BaseRestContoller {

    private static final Logger logger = Logger.getLogger(DeploymentsController.class.getName());
    private static final int MAX_NUMBER_OF_EVENTS = 100;
    public static final int REFRESH_INTERVAL_MILLIS = 500;
    private EventsCache eventsCache;

	@Autowired
	private UploadRepo repo;

	@Autowired
	private RestConfiguration restConfig;

	@Autowired
	private InstallServiceValidator[] installServiceValidators = new InstallServiceValidator[0];
	
//	@Autowired
//	private UninstallServiceValidator[] uninstallServiceValidators = new UninstallServiceValidator[0];

	@Autowired(required = false)
	private CustomPermissionEvaluator permissionEvaluator;

    /**
     * Initializing the uploadRepo which responsible for uploading and retrieving the files.
     * @throws java.io.IOException .
     */
    @PostConstruct
    public void init() throws IOException {
        this.eventsCache = new EventsCache(admin);
    }

    /**
     * This method provides metadata about a service belonging to a specific
     * application.
     *
     * @param appName
     *            - the application name the service belongs to.
     * @param serviceName
     *            - the service name.
     * @return - A {@link ServiceDetails} instance containing various metadata
     *         about the service.
     * @throws RestErrorException
     *             - In case an error happened while trying to retrieve the
     *             service.
     */
    @RequestMapping(value = "/{appName}/service/{serviceName}/metadata", method = RequestMethod.GET)
	public ServiceDetails getServiceDetails(@PathVariable final String appName,
			@PathVariable final String serviceName) throws RestErrorException {

		final Application application = admin.getApplications().waitFor(appName,
				DEFAULT_ADMIN_WAITING_TIMEOUT, TimeUnit.SECONDS);
		if (application == null) {
			throw new RestErrorException(
					CloudifyMessageKeys.APPLICATION_WAIT_TIMEOUT.getName(),
					appName, DEFAULT_ADMIN_WAITING_TIMEOUT, TimeUnit.SECONDS);
		}
		final ProcessingUnit processingUnit = application.getProcessingUnits()
				.waitFor(ServiceUtils.getAbsolutePUName(appName, serviceName),
						DEFAULT_ADMIN_WAITING_TIMEOUT, TimeUnit.SECONDS);
		if (processingUnit == null) {
			throw new RestErrorException(
					CloudifyMessageKeys.SERVICE_WAIT_TIMEOUT.getName(),
					serviceName, DEFAULT_ADMIN_WAITING_TIMEOUT,
					TimeUnit.SECONDS);
		}

		final ServiceDetails serviceDetails = new ServiceDetails();
		serviceDetails.setName(serviceName);
		serviceDetails.setApplicationName(appName);
		serviceDetails
				.setNumberOfInstances(processingUnit.getInstances().length);

		final List<String> instanceNaems = new ArrayList<String>();
		for (final ProcessingUnitInstance instance : processingUnit.getInstances()) {
			instanceNaems.add(instance.getProcessingUnitInstanceName());
		}
		serviceDetails.setInstanceNames(instanceNaems);

		return serviceDetails;
	}

	/**
	 * This method sets the given attributes to the application scope. Note that this action is Update or write. so the
	 * given attribute may not pre-exist.
	 * 
	 * @param appName
	 *            - the application name.
	 * @param attributesRequest
	 *            - An instance of {@link SetApplicationAttributesRequest} (as JSON) that holds the requested
	 *            attributes.
	 * @throws RestErrorException
	 *             rest error exception
	 */
	@RequestMapping(value = "/{appName}/attributes", method = RequestMethod.POST)
	public void setApplicationAttributes(@PathVariable final String appName,
			@RequestBody final SetApplicationAttributesRequest attributesRequest)
			throws RestErrorException {

		// valid application
		getApplication(appName);

		if (attributesRequest == null
				|| attributesRequest.getAttributes() == null) {
			throw new RestErrorException(
					CloudifyMessageKeys.EMPTY_REQUEST_BODY_ERROR.getName());
		}

		// set attributes
		setAttributes(appName, null, null, attributesRequest.getAttributes());

	}

	/**
	 * This method deletes a curtain attribute from the service instance scope.
	 * 
	 * @param appName
	 *            - the application name.
	 * @param serviceName
	 *            - the service name.
	 * @param instanceId
	 *            - the instance id.
	 * @param attributeName
	 *            - the required attribute to delete.
	 * @return - A {@link DeleteServiceInstanceAttributeResponse} instance it holds the deleted attribute previous value
	 * @throws RestErrorException
	 *             rest error exception when application , service not exist
	 */
	@RequestMapping(value = "/{appName}/service/{serviceName}/instances/{instanceId}/"
			+ "attributes/{attributeName}", method = RequestMethod.DELETE)
	public DeleteServiceInstanceAttributeResponse deleteServiceInstanceAttribute(
			@PathVariable final String appName,
			@PathVariable final String serviceName,
			@PathVariable final Integer instanceId,
			@PathVariable final String attributeName) throws RestErrorException {

		// valid service
		getService(appName, serviceName);

		// logger - request to delete attributes
		if (logger.isLoggable(Level.FINER)) {
			logger.finer("received request to delete attribute "
					+ attributeName + " of instance Id " + instanceId + " of service "
					+ ServiceUtils.getAbsolutePUName(appName, serviceName)
					+ " of application " + appName);
		}

		// get delete attribute returned previous value
		final Object previous = deleteAttribute(appName, serviceName, instanceId,
				attributeName);

		// create response object
		final DeleteServiceInstanceAttributeResponse siar = new DeleteServiceInstanceAttributeResponse();
		// set previous value
		siar.setPreviousValue(previous);
		// return response object
		return siar;

	}

	/******
	 * get service instance details. provides metadata about an instance with given application , service name
	 * 
	 * @param appName
	 *            the application name
	 * @param serviceName
	 *            the service name
	 * @param instanceId
	 *            the instance id
	 * @return service instance details {@link ServiceInstanceDetails}
	 * @throws RestErrorException
	 *             when application , service or service instance not exist
	 */
	@RequestMapping(value = "/{appName}/service/{serviceName}/instances/{instanceId}/metadata",
			method = RequestMethod.GET)
	public ServiceInstanceDetails getServiceInstanceDetails(
			@PathVariable final String appName,
			@PathVariable final String serviceName,
			@PathVariable final Integer instanceId) throws RestErrorException {

		// get processingUnit instance
		final ProcessingUnitInstance pui = getServiceInstance(appName, serviceName,
				instanceId);

		// get USM details
		final org.openspaces.pu.service.ServiceDetails usmDetails = pui
				.getServiceDetailsByServiceId("USM");
		// get attributes details
		final Map<String, Object> puiAttributes = usmDetails.getAttributes();

		// get private ,public IP
		final String privateIp = getServiceInstanceEnvVarible(pui,
				CloudifyConstants.GIGASPACES_AGENT_ENV_PRIVATE_IP);
		final String publicIp = getServiceInstanceEnvVarible(pui,
				CloudifyConstants.GIGASPACES_AGENT_ENV_PUBLIC_IP);

		// machine details
		final String hardwareId = getServiceInstanceEnvVarible(pui,
				CloudifyConstants.GIGASPACES_CLOUD_HARDWARE_ID);
		final String machineId = getServiceInstanceEnvVarible(pui,
				CloudifyConstants.GIGASPACES_CLOUD_MACHINE_ID);
		final String imageId = getServiceInstanceEnvVarible(pui,
				CloudifyConstants.GIGASPACES_CLOUD_IMAGE_ID);
		final String templateName = getServiceInstanceEnvVarible(pui,
				CloudifyConstants.GIGASPACES_CLOUD_TEMPLATE_NAME);

		// return new instance
		final ServiceInstanceDetails sid = new ServiceInstanceDetails();
		// set service instance details
		sid.setApplicationName(appName);
		sid.setServiceName(serviceName);
		sid.setServiceInstanceName(pui.getName());

		// set service instance machine details
		sid.setHardwareId(hardwareId);
		sid.setImageId(imageId);
		sid.setInstanceId(instanceId);
		sid.setMachineId(machineId);
		sid.setPrivateIp(privateIp);
		sid.setProcessDetails(puiAttributes);
		sid.setPublicIp(publicIp);
		sid.setTemplateName(templateName);

		return sid;

	}

	/******
	 * Installs an application.
	 * 
	 * @param appName
	 *            the application name.
	 * @return
	 */
	@RequestMapping(value = "/{name}", method = RequestMethod.POST)
	public void installApplication(@PathVariable final String appName) {
		throwUnsupported();
	}

	/******
	 * Installs an service.
	 * 
	 * @param appName
	 *            the application name.
	 * @param serviceName
	 *            the service name.
	 * @param request
	 *            the install-service request.
	 * @return {@link InstallServiceResponse} contains the lifecycleEventContainerID.
	 * @throws RestErrorException .
	 */
	@RequestMapping(value = "/{appName}/services/{serviceName}", method = RequestMethod.POST)
	public InstallServiceResponse installService(
			@PathVariable final String appName,
			@PathVariable final String serviceName,
			@RequestParam (value = CloudifyConstants.INSTALL_SERVICE_REQUEST_PARAM_NAME, required = true) 
			final InstallServiceRequest request) throws RestErrorException {

		final String absolutePuName = ServiceUtils.getAbsolutePUName(appName, serviceName);

		// get and extract service folder
		final File packedFile = getFromRepo(request.getServiceFolderUploadKey(), 
				CloudifyMessageKeys.WRONG_SERVICE_FOLDER_UPLOAD_KEY.getName(), absolutePuName);
		final File serviceDir = extractServiceDir(packedFile, absolutePuName);

		// update service properties file (and re-zip packedFile if needed).
		final File serviceOverridesFile = getFromRepo(request.getServiceOverridesUploadKey(),
				CloudifyMessageKeys.WRONG_SERVICE_OVERRIDES_UPLOAD_KEY.getName(), absolutePuName);
		final File workingProjectDir = new File(serviceDir, "ext");
		final File updatedPackedFile = updatePropertiesFile(request, serviceOverridesFile, serviceDir, absolutePuName,
				workingProjectDir, packedFile);

		// Read the service
		final Service service = readService(workingProjectDir, request.getServiceFileName(), absolutePuName);

		// update template name
		final String templateName = getTempalteNameFromService(service);

		// get cloud configuration file and content
		final File cloudConfigurationFile = getFromRepo(request.getCloudConfigurationUploadKey(),
				CloudifyMessageKeys.WRONG_CLOUD_CONFIGURATION_UPLOAD_KEY.getName(), absolutePuName);
		final byte[] cloudConfigurationContents = getCloudConfigurationContent(cloudConfigurationFile, absolutePuName);

		// get cloud overrides file
		final File cloudOverridesFile = getFromRepo(request.getCloudOverridesUploadKey(),
				CloudifyMessageKeys.WRONG_CLOUD_OVERRIDES_UPLOAD_KEY.getName(), absolutePuName);

		// update effective authGroups
		String effectiveAuthGroups = request.getAuthGroups();
		if (StringUtils.isBlank(effectiveAuthGroups)) {
			if (permissionEvaluator != null) {
				effectiveAuthGroups = permissionEvaluator.getUserAuthGroupsString();
			} else {
				effectiveAuthGroups = "";
			}
		}

		// validations
		validateInstallService(absolutePuName, request, service, templateName,
				cloudOverridesFile, serviceOverridesFile, cloudConfigurationFile);

		String cloudOverrides;
		try {
			cloudOverrides = FileUtils.readFileToString(cloudOverridesFile);
		} catch (IOException e) {
			throw new RestErrorException("failed reading cloud overrides file.", e);
		}
		// deploy
		final DeploymentConfig deployConfig = new DeploymentConfig();
		final UUID deploymentID = UUID.randomUUID();
		deployConfig.setDeploymentId(deploymentID.toString());
		deployConfig.setAbsolutePUName(absolutePuName);
		deployConfig.setApplicationName(appName);
		deployConfig.setCloud(restConfig.getCloud());
		deployConfig.setCloudConfig(cloudConfigurationContents);
		deployConfig.setCloudOverrides(cloudOverrides);
		final String locators = extractLocators(restConfig.getAdmin());
		deployConfig.setInstallRequest(request);
		deployConfig.setLocators(locators);
		deployConfig.setPackedFile(updatedPackedFile);
		deployConfig.setService(service);
		deployConfig.setTemplateName(templateName);
		deployConfig.setAuthGroups(effectiveAuthGroups);
		final ElasticDeploymentTopology deployment;
		final ElasticProcessingUnitDeploymentFactory fac = new ElasticProcessingUnitDeploymentFactoryImpl();
		try {
			deployment = fac.create(deployConfig);
		} catch (ElasticDeploymentCreationException e) {
			throw new RestErrorException("Failed creating deployment object.", e);
		}
		
		try {
			deployAndWait(serviceName, deployment);
		} catch (final TimeoutException e) {
			throw new RestErrorException("Timed out waiting for deployment.", e);
		}

		// start polling
		final InstallServiceResponse installServiceResponse = new InstallServiceResponse();
		if (!request.isApplicationInstall()) {
			startPollingForLifecycleEvents(deploymentID, service.getName(), appName,
					service.getNumInstances(), true, request.getTimeout(), request.getTimeUnit());
		}
		
		installServiceResponse.setDeploymentID(deploymentID.toString());
		return installServiceResponse;

	}

	private static String extractLocators(final Admin admin) {

		final LookupLocator[] locatorsArray = admin.getLocators();
		final StringBuilder locators = new StringBuilder();

		for (final LookupLocator locator : locatorsArray) {
			locators.append(locator.getHost()).append(':').append(locator.getPort()).append(',');
		}

		if (locators.length() > 0) {
			locators.setLength(locators.length() - 1);
		}

		return locators.toString();
	}

	private void deployAndWait(final String serviceName,
			final ElasticDeploymentTopology deployment) throws TimeoutException {
		GridServiceManager gsm = getGridServiceManager();
		ProcessingUnit pu = null;
		if (deployment instanceof ElasticStatelessProcessingUnitDeployment) {
			pu = gsm.deploy((ElasticStatelessProcessingUnitDeployment) deployment, 60, TimeUnit.SECONDS);
		} else if (deployment instanceof ElasticStatefulProcessingUnitDeployment) {
			pu = gsm.deploy((ElasticStatefulProcessingUnitDeployment) deployment, 60, TimeUnit.SECONDS);
		} else if (deployment instanceof ElasticSpaceDeployment) {
			pu = gsm.deploy((ElasticSpaceDeployment) deployment, 60, TimeUnit.SECONDS);
		}
		if (pu == null) {
			throw new TimeoutException("Timed out waiting for Service "
					+ serviceName + " deployment.");
		}
	}

	private GridServiceManager getGridServiceManager() {
		if (restConfig.getAdmin().getGridServiceManagers().isEmpty()) {
			throw new AdminException("Cannot locate Grid Service Manager");
		}
		return restConfig.getAdmin().getGridServiceManagers().iterator().next();
	}
	
	private void startPollingForLifecycleEvents(final UUID deploymentID, final String serviceName, 
			final String applicationName, final int plannedNumberOfInstances, final boolean isServiceInstall,
			final int timeout, final TimeUnit minutes) {
		
		RestPollingRunnable restPollingRunnable;
		logger.info("starting poll on service : " + serviceName + " app: " + applicationName);

		final LifecycleEventsContainer lifecycleEventsContainer = new LifecycleEventsContainer();
		lifecycleEventsContainer.setEventsSet(restConfig.getEventsSet());

		restPollingRunnable = new RestPollingRunnable(applicationName, timeout, minutes);
		restPollingRunnable.addService(serviceName, plannedNumberOfInstances);
		restPollingRunnable.setAdmin(restConfig.getAdmin());
		restPollingRunnable.setIsServiceInstall(isServiceInstall);
		restPollingRunnable.setLifecycleEventsContainer(lifecycleEventsContainer);
		restPollingRunnable.setEndTime(timeout, TimeUnit.MINUTES);
		restPollingRunnable.setIsSetInstances(true);
		restConfig.getLifecyclePollingThreadContainer().put(deploymentID, restPollingRunnable);
		final ScheduledFuture<?> scheduleWithFixedDelay = restConfig.getScheduledExecutor()
				.scheduleWithFixedDelay(restPollingRunnable, 0,
						CloudifyConstants.LIFECYCLE_EVENT_POLLING_INTERVAL_SEC, TimeUnit.SECONDS);
		restPollingRunnable.setFutureTask(scheduleWithFixedDelay);

		logger.log(Level.INFO, "polling container UUID is " + deploymentID.toString());
	}
	
	
	private UUID startPollingForServiceUninstallLifecycleEvents(final String serviceName, final String applicationName,
			final int timeoutInMinutes, final FutureTask<Boolean> undeployTask) {
	
		RestPollingRunnable restPollingRunnable;
		final LifecycleEventsContainer lifecycleEventsContainer = new LifecycleEventsContainer();
		final UUID deploymentID = UUID.randomUUID();
		lifecycleEventsContainer.setEventsSet(restConfig.getEventsSet());

		restPollingRunnable = new RestPollingRunnable(applicationName, timeoutInMinutes, TimeUnit.MINUTES);
		restPollingRunnable.addService(serviceName, 0);
		restPollingRunnable.setIsServiceInstall(false);
		restPollingRunnable.setIsUninstall(true);
		restPollingRunnable.setAdmin(restConfig.getAdmin());
		restPollingRunnable.setLifecycleEventsContainer(lifecycleEventsContainer);
		restPollingRunnable.setUndeployTask(undeployTask);
		restPollingRunnable.setEndTime(timeoutInMinutes, TimeUnit.MINUTES);
		restConfig.getLifecyclePollingThreadContainer().put(deploymentID, restPollingRunnable);
		final ScheduledFuture<?> scheduleWithFixedDelay = restConfig.getScheduledExecutor()
				.scheduleWithFixedDelay(restPollingRunnable, 0,
						CloudifyConstants.LIFECYCLE_EVENT_POLLING_INTERVAL_SEC, TimeUnit.SECONDS);
		restPollingRunnable.setFutureTask(scheduleWithFixedDelay);
		
		logger.log(Level.INFO, "Starting to poll for uninstall lifecycle events.");
		logger.log(Level.INFO, "polling container UUID is " + deploymentID.toString());
		
		return deploymentID;
	}
	
	
	/*private UUID startPollingForLifecycleUninstallEvents(final UUID existingDeploymentID, final String serviceName, final String applicationName,
			final int timeoutInMinutes, final FutureTask<Boolean> undeployTask) {
	
		RestPollingRunnable restPollingRunnable;
		final LifecycleEventsContainer lifecycleEventsContainer = new LifecycleEventsContainer();
		lifecycleEventsContainer.setEventsSet(restConfig.getEventsSet());
		UUID deploymentID = existingDeploymentID;
		if (deploymentID == null) {
			deploymentID = UUID.randomUUID();	
		}

		createRestPollingRunnableForUninstall(applicationName, timeoutInMinutes, serviceName);
		
		restPollingRunnable.setLifecycleEventsContainer(lifecycleEventsContainer);
		
		final ScheduledFuture<?> scheduleWithFixedDelay = restConfig.getScheduledExecutor()
				.scheduleWithFixedDelay(restPollingRunnable, 0,
						CloudifyConstants.LIFECYCLE_EVENT_POLLING_INTERVAL_SEC, TimeUnit.SECONDS);
		restPollingRunnable.setFutureTask(scheduleWithFixedDelay);
		
		restPollingRunnable = new RestPollingRunnable(applicationName, timeoutInMinutes, TimeUnit.MINUTES);
		restPollingRunnable.addService(serviceName, 0);
		restPollingRunnable.setIsServiceInstall(false);
		restPollingRunnable.setIsUninstall(true);
		restPollingRunnable.setAdmin(restConfig.getAdmin());
		restPollingRunnable.setLifecycleEventsContainer(lifecycleEventsContainer);
		restPollingRunnable.setUndeployTask(undeployTask);
		restPollingRunnable.setEndTime(timeoutInMinutes, TimeUnit.MINUTES);

		restConfig.getLifecyclePollingThreadContainer().put(deploymentID, restPollingRunnable);
		final ScheduledFuture<?> scheduleWithFixedDelay = restConfig.getScheduledExecutor()
				.scheduleWithFixedDelay(restPollingRunnable, 0,
						CloudifyConstants.LIFECYCLE_EVENT_POLLING_INTERVAL_SEC, TimeUnit.SECONDS);
		restPollingRunnable.setFutureTask(scheduleWithFixedDelay);
		
		logger.log(Level.INFO, "Starting to poll for uninstall lifecycle events.");
		logger.log(Level.INFO, "polling container UUID is " + deploymentID.toString());
		
		return deploymentID;
	}*/
	
	
	private RestPollingRunnable createRestPollingRunnableForUninstall(final String applicationName, final int timeoutInMinutes, final String serviceName) {
		RestPollingRunnable restPollingRunnable = new RestPollingRunnable(applicationName, timeoutInMinutes, TimeUnit.MINUTES);
		restPollingRunnable.addService(serviceName, 0);
		restPollingRunnable.setIsServiceInstall(false);
		restPollingRunnable.setIsUninstall(true);
		restPollingRunnable.setEndTime(timeoutInMinutes, TimeUnit.MINUTES);
		restPollingRunnable.setAdmin(restConfig.getAdmin());

		
		return restPollingRunnable;
	}
	
	

	private File extractServiceDir(final File srcFile, final String absolutePuName) throws RestErrorException {
		File serviceDir = null;
		try {
			// unzip srcFile into a new directory named absolutePuName under baseDir.
			final File baseDir =
					new File(restConfig.getTemporaryFolderPath(), CloudifyConstants.EXTRACTED_FILES_FOLDER_NAME);
			baseDir.mkdirs();
			baseDir.deleteOnExit();
			serviceDir = ServiceReader.extractProjectFileToDir(srcFile, absolutePuName, baseDir);
		} catch (final IOException e) {
			throw new RestErrorException(CloudifyMessageKeys.FAILED_TO_EXTRACT_PROJECT_FILE.getName(), 
					absolutePuName, e.getMessage());
		}
		return serviceDir;
	}

	private Service readService(final File workingProjectDir, final String serviceFileName, final String absolutePuName)
			throws RestErrorException {
		DSLServiceCompilationResult result;
		try {
			if (serviceFileName != null) {
				result = ServiceReader.getServiceFromFile(new File(
						workingProjectDir, serviceFileName), workingProjectDir);
			} else {
				result = ServiceReader.getServiceFromDirectory(workingProjectDir);
			}
		} catch (final Exception e) {
			throw new RestErrorException(CloudifyMessageKeys.FAILED_TO_READ_SERVICE.getName(), 
					absolutePuName, e.getMessage());
		}
		return result.getService();
	}

	private byte[] getCloudConfigurationContent(final File serviceCloudConfigurationFile, final String absolutePuName)
			throws RestErrorException {
		byte[] serviceCloudConfigurationContents = null;
		if (serviceCloudConfigurationFile != null) {
			try {
				serviceCloudConfigurationContents = FileUtils.readFileToByteArray(serviceCloudConfigurationFile);
			} catch (final IOException e) {
				throw new RestErrorException(CloudifyMessageKeys.FAILED_TO_READ_SERVICE_CLOUD_CONFIGURATION.getName(),
						absolutePuName, e.getMessage());
			}
		}
		return serviceCloudConfigurationContents;
	}

	private String getTempalteNameFromService(final Service service) {

		final Cloud cloud = restConfig.getCloud();
		if (cloud == null) {
			return null;
		}

		final ComputeDetails compute = service.getCompute();
		String templateName = restConfig.getDefaultTemplateName();
		if (compute != null) {
			templateName = compute.getTemplate();
		}
		if (IsolationUtils.isGlobal(service) && IsolationUtils.isUseManagement(service)) {
			final String managementTemplateName = cloud.getConfiguration().getManagementMachineTemplate();
			if (compute != null) {
				if (!StringUtils.isBlank(templateName)) {
					if (!templateName.equals(managementTemplateName)) {
						// this is just a clarification log.
						// the service wont be installed on a management machine(even if there is enough memory)
						// because the management machine template does not match the desired template
						logger.warning("Installation of service " + service.getName() + " on a management machine "
								+ "will not be attempted since the specified template(" + templateName + ")"
								+ " is different than the management machine template(" + managementTemplateName + ")");
					}
				}
			} else {
				templateName = restConfig.getManagementTemplateName();
			}
		}
		return templateName;
	}

	/**
	 * Merge service properties file with application properties file and service overrides file. Merge all into the
	 * service properties file (create one if doesn't exist).
	 * 
	 * @param request
	 * @param serviceDir
	 * @param absolutePuName
	 * @param workingProjectDir
	 * @param srcFile
	 * @return The zip file with the updated properties file or the original zip file if no update needed.
	 * @throws RestErrorException
	 */
	private File updatePropertiesFile(final InstallServiceRequest request, final File overridesFile,
			final File serviceDir, final String absolutePuName, final File workingProjectDir, final File srcFile)
			throws RestErrorException {
		final File applicationProeprtiesFile = request.getApplicationPropertiesFile();
		// check if merge is necessary
		if (overridesFile == null && applicationProeprtiesFile == null) {
			return srcFile;
		} else {
			// get properties file from working directory
			final String propertiesFileName =
					DSLUtils.getPropertiesFileName(workingProjectDir, DSLUtils.SERVICE_DSL_FILE_NAME_SUFFIX);
			final File servicePropertiesFile = new File(workingProjectDir, propertiesFileName);
			final LinkedHashMap<File, String> filesToAppend = new LinkedHashMap<File, String>();
			try {
				// append application properties, service properties and overrides files
				final FileAppender appender = new FileAppender("finalPropertiesFile.properties");
				filesToAppend.put(applicationProeprtiesFile, "application proeprties file");
				filesToAppend.put(servicePropertiesFile, "service proeprties file");
				if (overridesFile != null) {
					filesToAppend.put(overridesFile, "service overrides file");
				}
				appender.appendAll(servicePropertiesFile, filesToAppend);
				return Packager.createZipFile(absolutePuName, serviceDir);
			} catch (final IOException e) {
				throw new RestErrorException(CloudifyMessageKeys.FAILED_TO_MERGE_OVERRIDES.getName(), 
						absolutePuName, e.getMessage());
			}
		}
	}

	private File getFromRepo(final String uploadKey, final String errorMsg, final String absolutePuName)
			throws RestErrorException {
		if (StringUtils.isBlank(uploadKey)) {
			return null;
		}
		final File file = repo.get(uploadKey);
		if (file == null) {
			throw new RestErrorException(errorMsg, uploadKey, absolutePuName);
		}
		return file;
	}

	private void validateInstallService(final String absolutePuName, final InstallServiceRequest request,
			final Service service, final String templateName, final File cloudOverridesFile,
			final File serviceOverridesFile, final File cloudConfigurationFile)
			throws RestErrorException {
		final InstallServiceValidationContext validationContext = new InstallServiceValidationContext();
		validationContext.setAbsolutePuName(absolutePuName);
		validationContext.setCloud(restConfig.getCloud());
		validationContext.setRequest(request);
		validationContext.setService(service);
		validationContext.setTemplateName(templateName);
		validationContext.setCloudOverridesFile(cloudOverridesFile);
		validationContext.setServiceOverridesFile(serviceOverridesFile);
		validationContext.setCloudConfigurationFile(cloudConfigurationFile);
		for (final InstallServiceValidator validator : getInstallServiceValidators()) {
			validator.validate(validationContext);
		}
	}
	
	private void validateUninstallService()
			throws RestErrorException {
//		final UninstallServiceValidationContext validationContext = new UninstallServiceValidationContext();
//
//		validationContext.setCloud(restConfig.getCloud());
//
//		for (final UninstallServiceValidator validator : getUninstallServiceValidators()) {
//			validator.validate(validationContext);
//		}
	}

	/******
	 * get application status by given name.
	 * 
	 * @param appName
	 *            the application name.
	 * @return
	 */
	@RequestMapping(value = "/{appName}", method = RequestMethod.GET)
	public void getApplicationStatus(@PathVariable final String appName) {
		throwUnsupported();
	}

	/******
	 * get Service status by given name.
	 * 
	 * @param appName
	 *            the application name
	 * @param serviceName
	 *            the service name.
	 * @return
	 */
	@RequestMapping(value = "/{appName}/services/{serviceName}", method = RequestMethod.GET)
	public void getServiceStatus(@PathVariable final String appName,
			@PathVariable final String serviceName) {
		throwUnsupported();
	}

	/******
	 * update application by given name.
	 * 
	 * @param appName
	 *            the application name.
	 * @return
	 */
	@RequestMapping(value = "/{appName}", method = RequestMethod.PUT)
	public void updateApplication(@PathVariable final String appName) {
		throwUnsupported();
	}

	/******
	 * update service by given name.
	 * 
	 * @param appName
	 *            the application name
	 * @param serviceName
	 *            the service name
	 * @return
	 */
	@RequestMapping(value = "/{appName}/services/{serviceName}", method = RequestMethod.PUT)
	public void updateService(@PathVariable final String appName,
			@PathVariable final String serviceName) {
		throwUnsupported();
	}

	/**
	 * uninstall an application by given name.
	 * 
	 * @param appName
	 *            application name
	 */
	@RequestMapping(value = "/{appName}", method = RequestMethod.DELETE)
	public void uninstallApplication(@PathVariable final String appName) {
		throwUnsupported();
	}

	/******
	 * uninstall a service by given name.
	 * 
	 * @param appName
	 *            the application name
	 * @param serviceName
	 *            the service name
	 * @param uninstallServiceRequest
	 *            settings regarding this request (e.g. timeout)
	 * @throws RestErrorException
	 *            Indicates the operation failed
	 */
	/*@RequestMapping(value = "/{appName}/services/{serviceName}", method = RequestMethod.DELETE)
	@PreAuthorize("isFullyAuthenticated()")
	public UninstallServiceResponse uninstallService(@PathVariable final String appName,
			@PathVariable final String serviceName,
			@RequestBody final UninstallServiceRequest uninstallServiceRequest) throws RestErrorException {
		final String absolutePuName = ServiceUtils.getAbsolutePUName(appName, serviceName);
		final ProcessingUnit processingUnit = admin.getProcessingUnits().waitFor(absolutePuName, 
				PU_DISCOVERY_TIMEOUT_SEC, TimeUnit.SECONDS);
		
		if (processingUnit == null) {
			throw new RestErrorException(FAILED_TO_LOCATE_SERVICE, serviceName);
		}

		if (permissionEvaluator != null) {
			final String puAuthGroups = processingUnit.getBeanLevelProperties().getContextProperties().
					getProperty(CloudifyConstants.CONTEXT_PROPERTY_AUTH_GROUPS);
			final Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
			final CloudifyAuthorizationDetails authDetails = new CloudifyAuthorizationDetails(authentication);
			permissionEvaluator.verifyPermission(authDetails, puAuthGroups, "deploy");
		}
		
		// validations
		validateUninstallService();

		final FutureTask<Boolean> undeployTask = new FutureTask<Boolean>(
				new Callable<Boolean>() {
					@Override
					public Boolean call() throws Exception {
						boolean result = processingUnit.undeployAndWait(uninstallServiceRequest.getTimeoutInMinutes(),
								TimeUnit.MINUTES);
						deleteServiceAttributes(appName, serviceName);
						return result;
					}

				});
		serviceUndeployExecutor.execute(undeployTask);
		final UUID lifecycleEventContainerID = startPollingForServiceUninstallLifecycleEvents(appName, serviceName,
				uninstallServiceRequest.getTimeoutInMinutes(), undeployTask);

		final UninstallServiceResponse uninstallServiceResponse = new UninstallServiceResponse();
		uninstallServiceResponse.setDeploymentID(lifecycleEventContainerID.toString());
		return uninstallServiceResponse;
	}*/

	/**
	 * update application attributes.
	 * 
	 * @param appName
	 *            the application name
	 * @param attributeName
	 *            the attribute name
	 * @param updateApplicationAttributeRequest
	 *            update application attribute request {@link updateApplicationAttributeRequest}
	 */
	@RequestMapping(value = "/{appName}/attributes/{attributeName}", method = RequestMethod.PUT)
	public void updateApplicationAttribute(
			@PathVariable final String appName,
			@PathVariable final String attributeName,
			@RequestBody final UpdateApplicationAttributeRequest updateApplicationAttributeRequest) {

		throwUnsupported();
	}

	/**
	 * get application attributes.
	 * 
	 * @param appName
	 *            the application name
	 * @return {@link ApplicationAttributesResponse} application attribute response
	 * @throws RestErrorException
	 *             when application not exist
	 */
	@RequestMapping(value = "/{appName}/attributes", method = RequestMethod.GET)
	public GetApplicationAttributesResponse getApplicationAttribute(
			@PathVariable final String appName) throws RestErrorException {

		// valid application if exist
		getApplication(appName);

		if (logger.isLoggable(Level.FINER)) {
			logger.finer("received request to get all attributes of application "
					+ appName);
		}

		// get attributes
		final Map<String, Object> attributes = getAttributes(appName, null, null);

		// create response object
		final GetApplicationAttributesResponse aar = new GetApplicationAttributesResponse();
		// set attributes
		aar.setAttributes(attributes);
		return aar;

	}

	/**
	 * delete application attribute.
	 * 
	 * @param appName
	 *            the application name
	 * @param attributeName
	 *            attribute name to delete
	 * @return {@link DeleteApplicationAttributeResponse}
	 * @throws RestErrorException
	 *             rest error exception when application not exist
	 */
	@RequestMapping(value = "/{appName}/attributes/{attributeName}", method = RequestMethod.DELETE)
	public DeleteApplicationAttributeResponse deleteApplicationAttribute(
			@PathVariable final String appName,
			@PathVariable final String attributeName)
			throws RestErrorException {

		// valid application if exist
		getApplication(appName);

		if (logger.isLoggable(Level.FINER)) {
			logger.finer("received request to delete attributes "
					+ attributeName + " of application " + appName);
		}

		// delete attribute returned previous value
		final Object previousValue = deleteAttribute(appName, null,
				null, attributeName);

		final DeleteApplicationAttributeResponse daar = new DeleteApplicationAttributeResponse();
		daar.setPreviousValue(previousValue);

		return daar;

	}

	/******
	 * get service attribute by given name.
	 * 
	 * @param appName
	 *            the application name
	 * @param serviceName
	 *            the service name
	 * @return {@link ServiceAttributesResponse}
	 * @throws RestErrorException
	 *             rest error exception when application , service not exist
	 */
	@RequestMapping(value = "/{appName}/service/{serviceName}/attributes", method = RequestMethod.GET)
	public GetServiceAttributesResponse getServiceAttribute(
			@PathVariable final String appName,
			@PathVariable final String serviceName) throws RestErrorException {

		// valid exist service
		getService(appName, serviceName);

		// logger - request to get all attributes
		if (logger.isLoggable(Level.FINER)) {
			logger.finer("received request to get all attributes of service "
					+ ServiceUtils.getAbsolutePUName(appName, serviceName)
					+ " of application " + appName);
		}

		// get attributes
		final Map<String, Object> attributes = getAttributes(appName, serviceName,
				null);

		// create response object
		final GetServiceAttributesResponse sar = new GetServiceAttributesResponse();
		// set attributes
		sar.setAttributes(attributes);
		// return response object
		return sar;

	}

	/******
	 * set service attribute by given name.
	 * 
	 * @param appName
	 *            the application name
	 * @param serviceName
	 *            the service name
	 * @param request
	 *            service attributes request
	 * @return
	 * @throws RestErrorException
	 *             rest error exception
	 */
	@RequestMapping(value = "/{appName}/service/{serviceName}/attributes", method = RequestMethod.POST)
	public void setServiceAttribute(@PathVariable final String appName,
			@PathVariable final String serviceName,
			@RequestBody final SetServiceAttributesRequest request)
			throws RestErrorException {

		// valid service
		getService(appName, serviceName);

		// validate request object
		if (request == null || request.getAttributes() == null) {
			throw new RestErrorException(
					CloudifyMessageKeys.EMPTY_REQUEST_BODY_ERROR.getName());
		}

		if (logger.isLoggable(Level.FINER)) {
			logger.finer("received request to set attributes "
					+ request.getAttributes().keySet() + " of service "
					+ ServiceUtils.getAbsolutePUName(appName, serviceName)
					+ " of application " + appName + " to: "
					+ request.getAttributes().values());

		}

		// set attributes
		setAttributes(appName, serviceName, null, request.getAttributes());

	}

	/******
	 * update service attribute by given name.
	 * 
	 * @param appName
	 *            the application name
	 * @param serviceName
	 *            the service name
	 * @return
	 */
	@RequestMapping(value = "/{appName}/service/{serviceName}/attributes", method = RequestMethod.PUT)
	public void updateServiceAttribute(@PathVariable final String appName,
			@PathVariable final String serviceName) {
		throwUnsupported();
	}

	/******
	 * delete service attribute by given name.
	 * 
	 * @param appName
	 *            the application name
	 * @param serviceName
	 *            the service name
	 * @param attributeName
	 *            attribute name to delete
	 * @return {@link DeleteServiceAttributeResponse}
	 * @throws RestErrorException
	 *             when attribute name is empty,null or application name ,service not exist
	 */
	@RequestMapping(value = "/{appName}/service/{serviceName}/attributes/{attributeName}", method = RequestMethod.DELETE)
	public DeleteServiceAttributeResponse deleteServiceAttribute(
			@PathVariable final String appName,
			@PathVariable final String serviceName,
			@PathVariable final String attributeName)
			throws RestErrorException {

		// valid service
		getService(appName, serviceName);

		// logger - request to delete attributes
		if (logger.isLoggable(Level.FINER)) {
			logger.finer("received request to delete attribute "
					+ attributeName + " of service "
					+ ServiceUtils.getAbsolutePUName(appName, serviceName)
					+ " of application " + appName);
		}

		// get delete attribute returned previous value
		final Object previous = deleteAttribute(appName,
                serviceName, null, attributeName);

		// create response object
		final DeleteServiceAttributeResponse sar = new DeleteServiceAttributeResponse();
		// set previous value
		sar.setPreviousValue(previous);
		// return response object
		return sar;

	}

	/******
	 * get service instance attribute by given name.
	 * 
	 * @param appName
	 *            the application name
	 * @param serviceName
	 *            the service name
	 * @param instanceId
	 *            the instance id
	 * @return ServiceInstanceAttributesResponse
	 * @throws RestErrorException
	 *             rest error exception when application , service not exist
	 */
	@RequestMapping(value = "/{appName}/service/{serviceName}/instances/{instanceId}/attributes", method = RequestMethod.GET)
	public GetServiceInstanceAttributesResponse getServiceInstanceAttribute(
			@PathVariable final String appName,
			@PathVariable final String serviceName,
			@PathVariable final Integer instanceId) throws RestErrorException {

		// valid service
		getService(appName, serviceName);

		// logger - request to get all attributes
		if (logger.isLoggable(Level.FINER)) {
			logger.finer("received request to get all attributes of instance number "
					+ instanceId
					+ " of service "
					+ ServiceUtils.getAbsolutePUName(appName, serviceName)
					+ " of application " + appName);
		}

		// get attributes
		final Map<String, Object> attributes = getAttributes(appName, serviceName,
				instanceId);
		// create response object
		final GetServiceInstanceAttributesResponse siar = new GetServiceInstanceAttributesResponse();
		// set attributes
		siar.setAttributes(attributes);
		// return response object
		return siar;

	}

	/******
	 * set service instance attribute by given name , id.
	 * 
	 * @param appName
	 *            the application name
	 * @param serviceName
	 *            the service name
	 * @param instanceId
	 *            the instance id
	 * @param request
	 *            service instance attributes request
	 * @return
	 * @throws RestErrorException
	 *             rest error exception when application or service not exist
	 */
	@RequestMapping(value = "/{appName}/service/{serviceName}/instances/{instanceId}/attributes", method = RequestMethod.POST)
	public void setServiceInstanceAttribute(@PathVariable final String appName,
			@PathVariable final String serviceName,
			@PathVariable final Integer instanceId,
			@RequestBody final SetServiceInstanceAttributesRequest request)
			throws RestErrorException {

		// valid service
		getService(appName, serviceName);

		// validate request object
		if (request == null || request.getAttributes() == null) {
			throw new RestErrorException(
					CloudifyMessageKeys.EMPTY_REQUEST_BODY_ERROR.getName());
		}

		if (logger.isLoggable(Level.FINER)) {
			logger.finer("received request to set attribute "
					+ request.getAttributes().keySet() + " of instance number "
					+ instanceId + " of service "
					+ ServiceUtils.getAbsolutePUName(appName, serviceName)
					+ " of application " + appName + " to: "
					+ request.getAttributes().values());
		}

		// set attributes
		setAttributes(appName, serviceName, instanceId, request.getAttributes());

	}

	/******
	 * update service instance attribute by given name , id.
	 * 
	 * @param appName
	 *            the application name
	 * @param serviceName
	 *            the service name
	 * @param instanceId
	 *            the instance id
	 * @return
	 */
	@RequestMapping(value = "/{appName}/service/{serviceName}/instances/{instanceId}/attributes", method = RequestMethod.PUT)
	public void updateServiceInstanceAttribute(
			@PathVariable final String appName,
			@PathVariable final String serviceName,
			@PathVariable final String instanceId) {
		throwUnsupported();
	}

	/******
	 * get service metrics by given service name.
	 * 
	 * @param appName
	 *            the application name
	 * @param serviceName
	 *            the service name
	 * 
	 * 
	 * @return ServiceMetricsResponse instance
	 * @throws RestErrorException
	 *             rest error exception
	 */
	@RequestMapping(value = "/{appName}/service/{serviceName}/metrics", method = RequestMethod.GET)
	public ServiceMetricsResponse getServiceMetrics(
			@PathVariable final String appName,
			@PathVariable final String serviceName) throws RestErrorException {

		// service instances metrics data
		final List<ServiceInstanceMetricsData> serviceInstanceMetricsDatas =
				new ArrayList<ServiceInstanceMetricsData>();

		// get service
		final ProcessingUnit service = getService(appName, serviceName);

		// set metrics for every instance
		for (final ProcessingUnitInstance serviceInstance : service.getInstances()) {

			final Map<String, Object> metrics = serviceInstance.getStatistics()
					.getMonitors().get("USM").getMonitors();
			serviceInstanceMetricsDatas.add(new ServiceInstanceMetricsData(
					serviceInstance.getInstanceId(), metrics));

		}

		// create response instance
		final ServiceMetricsResponse smr = new ServiceMetricsResponse();
		smr.setAppName(appName);
		smr.setServiceInstaceMetricsData(serviceInstanceMetricsDatas);
		smr.setServiceName(serviceName);

		return smr;

	}

	/******
	 * get service instance metrics by given specific instanceId.
	 * 
	 * @param appName
	 *            the application name
	 * @param serviceName
	 *            the service name
	 * @param instanceId
	 *            the instance name
	 * @return ServiceInstanceMetricsResponse {@link ServiceInstanceMetricsResponse}
	 * @throws RestErrorException
	 *             rest error exception
	 */
	@RequestMapping(value = "{appName}/service/{serviceName}/instances/{instanceId}/metrics", method = RequestMethod.GET)
	public ServiceInstanceMetricsResponse getServiceInstanceMetrics(
			@PathVariable final String appName,
			@PathVariable final String serviceName,
			@PathVariable final Integer instanceId) throws RestErrorException {

		// get service instance
		final ProcessingUnitInstance serviceInstance = getServiceInstance(appName,
				serviceName, instanceId);

		// get metrics data
		final Map<String, Object> metrics = serviceInstance.getStatistics()
				.getMonitors().get("USM").getMonitors();

		final ServiceInstanceMetricsData serviceInstanceMetricsData = new ServiceInstanceMetricsData(
				instanceId, metrics);

		// create response object
		final ServiceInstanceMetricsResponse simr = new ServiceInstanceMetricsResponse();

		// set response data
		simr.setAppName(appName);
		simr.setServiceName(serviceName);
		simr.setServiceInstanceMetricsData(serviceInstanceMetricsData);

		return simr;
	}

	/******
	 * set service details by given name.
	 * 
	 * @param appName
	 *            the application name
	 * @param serviceName
	 *            the service name
	 * @return
	 */
	@RequestMapping(value = "/{appName}/service/{serviceName}/metadata", method = RequestMethod.POST)
	public void setServiceDetails(@PathVariable final String appName,
			@PathVariable final String serviceName) {
		throwUnsupported();
	}

	/******
	 * update service details by given name.
	 * 
	 * @param appName
	 *            the application name
	 * @param serviceName
	 *            the service name
	 * @return
	 */
	@RequestMapping(value = "/{appName}/service/{serviceName}/metadata", method = RequestMethod.PUT)
	public void updateServiceDetails(@PathVariable final String appName,
			@PathVariable final String serviceName) {
		throwUnsupported();
	}

	/******
	 * set service instance details by given name.
	 * 
	 * @param appName
	 *            the application name
	 * @param serviceName
	 *            the service name
	 * @param instanceId
	 *            the instance id
	 * @return
	 */
	@RequestMapping(value = "/{appName}/service/{serviceName}/instances/{instanceId}/metadata", method = RequestMethod.POST)
	public void setServiceInstanceDetails(@PathVariable final String appName,
			@PathVariable final String serviceName,
			@PathVariable final String instanceId) {
		throwUnsupported();
	}

	/******
	 * get service alert by given name.
	 * 
	 * @param appName
	 *            the application name
	 * @param serviceName
	 *            the service name
	 * @return
	 */
	@RequestMapping(value = "/appName}/service/{serviceName}/alerts", method = RequestMethod.GET)
	public void getServiceAlerts(@PathVariable final String appName,
			@PathVariable final String serviceName) {
		throwUnsupported();
	}

	public UploadRepo getRepo() {
		return repo;
	}

	public void setRepo(final UploadRepo repo) {
		this.repo = repo;
	}

	public InstallServiceValidator[] getInstallServiceValidators() {
		return installServiceValidators;
	}
	
//	public UninstallServiceValidator[] getUninstallServiceValidators() {
//		return uninstallServiceValidators;
//	}

	public void setInstallServiceValidators(final InstallServiceValidator[] installServiceValidators) {
		this.installServiceValidators = installServiceValidators;
	}
	
//	public void setUninstallServiceValidators(final UninstallServiceValidator[] uninstallServiceValidators) {
//		this.uninstallServiceValidators = uninstallServiceValidators;
//	}


    @RequestMapping(value = "applications/{appName}/service/{serviceName}/events", method = RequestMethod.GET)
    public ServiceDeploymentEvents getServiceDeploymentEventsByFullServiceName(@PathVariable final String appName,
                                                                               @PathVariable final String serviceName,
                                                                               @RequestParam(required = false, defaultValue = "0") final int from,
                                                                               @RequestParam(required = false, defaultValue = "-1") final int to)
                                                                               throws Throwable {

        // limit the default number of events returned to the client.
        int actualTo = to;
        if (to == -1) {
            actualTo = from + MAX_NUMBER_OF_EVENTS;
        }

        EventsCacheKey key = new EventsCacheKey(appName, serviceName);
        logger.fine(EventsUtils.getThreadId() + "Received request for events [" + from + "]-[" + to + "] . key : " + key);
        EventsCacheValue value;
        try {
            logger.fine(EventsUtils.getThreadId() + "Retrieving events from cache for key : " + key);
            value = eventsCache.get(key);
        } catch (final ExecutionException e) {
            throw e.getCause();
        }

        // we don't want another request to modify our object during this calculation.
        synchronized (value.getMutex()) {
            if (!EventsUtils.eventsPresent(value.getEvents(), from, actualTo)) {
                // enforce time restriction on refresh operations.
                long now = System.currentTimeMillis();
                if (now - value.getLastRefreshedTimestamp() > REFRESH_INTERVAL_MILLIS) {
                    logger.fine(EventsUtils.getThreadId() + "Some events are missing from cache. Refreshing...");
                    // refresh the cache for this deployment.
                    eventsCache.refresh(key);
                }
            } else {
                logger.fine(EventsUtils.getThreadId() + "Found all desired events in cache.");
            }

            // return the events. this MAY or MAY NOT be the complete set of events requested.
            // request for specific events is treated as best effort. no guarantees all events are returned.
            return EventsUtils.extractDesiredEvents(value.getEvents(), from, actualTo);
        }
    }





    @RequestMapping(value = "{deploymentId}/events", method = RequestMethod.GET)
    public ServiceDeploymentEvents getServiceDeploymentEventsByDeploymentId(@PathVariable final String deploymentId,
                                                                            @RequestParam(required = false, defaultValue = "0") final String from,
                                                                            @RequestParam(required = false, defaultValue = "-1") final String to) {
        return null;
    }
}
