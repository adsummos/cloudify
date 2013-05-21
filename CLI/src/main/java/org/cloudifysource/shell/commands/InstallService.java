/*******************************************************************************
 * Copyright (c) 2011 GigaSpaces Technologies Ltd. All rights reserved
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
package org.cloudifysource.shell.commands;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.felix.gogo.commands.Argument;
import org.apache.felix.gogo.commands.Command;
import org.apache.felix.gogo.commands.Option;
import org.cloudifysource.dsl.Service;
import org.cloudifysource.dsl.internal.CloudifyConstants;
import org.cloudifysource.dsl.internal.CloudifyErrorMessages;
import org.cloudifysource.dsl.internal.DSLErrorMessageException;
import org.cloudifysource.dsl.internal.debug.DebugModes;
import org.cloudifysource.dsl.internal.debug.DebugUtils;
import org.cloudifysource.dsl.internal.packaging.ZipUtils;
import org.cloudifysource.dsl.rest.request.InstallServiceRequest;
import org.cloudifysource.dsl.rest.response.InstallServiceResponse;
import org.cloudifysource.dsl.utils.RecipePathResolver;
import org.cloudifysource.restclient.RestClientException;
import org.cloudifysource.shell.Constants;
import org.cloudifysource.shell.ShellUtils;
import org.cloudifysource.shell.rest.RestLifecycleEventsLatch;
import org.fusesource.jansi.Ansi.Color;

import java.io.File;
import java.io.IOException;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * @author rafi, adaml, barakm
 * @since 2.0.0
 * 
 *        Installs a service by deploying the service files as one packed file (zip, war or jar). Service files can also
 *        be supplied as a folder containing multiple files.
 * 
 *        Required arguments: service-file - Path to the service's packed file or folder
 * 
 *        Optional arguments: zone - The machines zone in which to install the service name - The name of the service
 *        timeout - The number of minutes to wait until the operation is completed (default: 5 minutes)
 * 
 *        Command syntax: install-service [-zone zone] [-name name] [-timeout timeout] service-file
 */
@Command(scope = "cloudify", name = "install-service", description = "Installs a service. If you specify a folder"
		+ " path it will be packed and deployed. If you specify a service archive, the shell will deploy that file.")
public class InstallService extends AdminAwareCommand {

    private static final long MILLIS_IN_MINUTES = 60 * 1000;

	private static final int DEFAULT_TIMEOUT_MINUTES = 5;
	private static final String TIMEOUT_ERROR_MESSAGE = "Service installation timed out."
			+ " Configure the timeout using the -timeout flag.";
	private static final long TEN_K = 10 * FileUtils.ONE_KB;

	@Argument(required = true, name = "recipe", description = "The service recipe folder or archive")
	private File recipe = null;

	@Option(required = false, name = "-authGroups", description = "The groups authorized to access this application "
			+ "(multiple values can be comma-separated)")
	private String authGroups = null;

	@Option(required = false, name = "-name", description = "The name of the service")
	private String serviceName = null;

	@Option(required = false, name = "-timeout", description = "The number of minutes to wait until the operation is "
			+ "done. Defaults to 5 minutes.")
	private int timeoutInMinutes = DEFAULT_TIMEOUT_MINUTES;

	@Option(required = false, name = "-service-file-name", description = "Name of the service file in the "
			+ "recipe folder. If not specified, uses the default file name")
	private String serviceFileName = null;

	@Option(required = false, name = "-cloudConfiguration", description =
			"File of directory containing configuration information to be used by the cloud driver "
					+ "for this application")
	private File cloudConfiguration;

	@Option(required = false, name = "-disableSelfHealing",
			description = "Disables service self healing")
	private boolean disableSelfHealing = false;

	@Option(required = false, name = "-overrides", description =
			"File containing properties to be used to overrides the current service's properties.")
	private File overrides = null;

	@Option(required = false, name = "-cloud-overrides",
			description = "File containing properties to be used to override the current cloud "
					+ "configuration for this service.")
	private File cloudOverrides = null;

	@Option(required = false, name = "-debug-all",
			description = "Debug all supported lifecycle events")
	private boolean debugAll;

	@Option(required = false, name = "-debug-events",
			description = "Debug the specified events")
	private String debugEvents;

	@Option(required = false, name = "-debug-mode",
			description = "Debug mode. One of: instead, after or onError")
	private String debugModeString = DebugModes.INSTEAD.getName();

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected Object doExecute() throws Exception {

        // first run validations
        validateDebugSetting();
        validateOverridesFile();
        validateCloudOverridesFile();
        validateCloudConfigurationFile();

        // now upload the files if necessary
        final String cloudConfigurationFileKey = uploadToRepo(cloudConfiguration);
        final String cloudOverridesFileKey = uploadToRepo(cloudOverrides);
        final String overridesFileKey = uploadToRepo(overrides);

        NameAndPackedFileResolver nameAndPackedFileResolver = getResolver(recipe);
        nameAndPackedFileResolver.init();
        String actualServiceName = nameAndPackedFileResolver.getName();
        File packedFile = nameAndPackedFileResolver.getPackedFile();

        final String recipeFileKey = uploadToRepo(packedFile);

        InstallServiceRequest request = new InstallServiceRequest();
        request.setAuthGroups(authGroups);
        request.setCloudConfigurationUploadKey(cloudConfigurationFileKey);
        request.setDebugAll(debugAll);
        request.setCloudOverridesUploadKey(cloudOverridesFileKey);
        request.setDebugEvents(debugEvents);
        request.setServiceOverridesUploadKey(overridesFileKey);
        request.setServiceFolderUploadKey(recipeFileKey);
        request.setServiceFileName(serviceFileName);
        request.setSelfHealing(disableSelfHealing);
        request.setTimeoutInMillis(timeoutInMinutes * MILLIS_IN_MINUTES);

        // execute the request
        InstallServiceResponse installServiceResponse = restClient.installService(CloudifyConstants.DEFAULT_APPLICATION_NAME, actualServiceName, request);
        final String deploymentId = installServiceResponse.getDeploymentID();

        // start polling for life cycle events
        waitForLifeCycleEvents(deploymentId);

		return getFormattedMessage("service_install_ended", Color.GREEN, serviceName);
	}

    private NameAndPackedFileResolver getResolver(File recipe) throws CLIStatusException {
        if (recipe.isFile()) {
            // this is a prepared package we can just use.
            return new PreparedPackageHelper(recipe);
        } else {
            // this is an actual service directory
            recipe = resolve(recipe);
            return new ServiceHelper(recipe, overrides, serviceFileName);
        }
    }

    private String getServiceNameFromRecipe() {
        return null;
    }

    private void waitForLifeCycleEvents(final String deploymentId) {



    }

    private String uploadToRepo(final File file) throws RestClientException, IOException, TimeoutException {
        if (file == null) {
            return null;
        }
        return restClient.upload(file.getName(), file).getUploadKey();
    }

    private File zip(final File file) throws IOException {

        // create a temp file in a temp directory
        final File tempDir = File.createTempFile("__cloudify_temp", ".tmp");
        FileUtils.forceDelete(tempDir);
        if (!tempDir.mkdirs()) {
            throw new IOException("Failed creating directories in path " + tempDir.getAbsolutePath());
        }

        final File tempFile = new File(tempDir, file.getName());

        // mark files for deletion on JVM exit
        tempFile.deleteOnExit();
        tempDir.deleteOnExit();

        if (file.isDirectory()) {
            ZipUtils.zip(file, tempFile);
        } else if (file.isFile()) {
            ZipUtils.zipSingleFile(file, tempFile);
        }
        return tempFile;
    }

    private void validateCloudConfigurationFile() throws CLIStatusException {
        if (cloudConfiguration != null) {
            if (!cloudConfiguration.exists()) {
                throw new CLIStatusException("cloud_configuration_file_not_found",
                        cloudConfiguration.getAbsolutePath());
            }
            if (!cloudConfiguration.isDirectory() && !cloudConfiguration.isFile()) {
                throw new CLIStatusException("cloud_configuration_file_invalid",
                        cloudConfiguration.getAbsolutePath());
            }

        }
    }

    private File resolve(final File recipe) throws CLIStatusException {
        final RecipePathResolver pathResolver = new RecipePathResolver();
        if (pathResolver.resolveService(recipe)) {
            return pathResolver.getResolved();
        } else {
            throw new CLIStatusException("service_file_doesnt_exist",
                    StringUtils.join(pathResolver.getPathsLooked().toArray(), ", "));
        }
    }

    private void validateCloudOverridesFile() throws CLIStatusException {
        if (cloudOverrides != null) {
            if (cloudOverrides.length() >= TEN_K) {
                throw new CLIStatusException(CloudifyErrorMessages.OVERRIDES_TO_LONG.getName());
            }
        }
    }

    private void validateOverridesFile() throws CLIStatusException {
        if (overrides != null) {
            if (overrides.length() >= TEN_K) {
                throw new CLIStatusException(CloudifyErrorMessages.CLOUD_OVERRIDES_TO_LONG.getName());
            }
        }
    }

    private void validateDebugSetting() throws CLIStatusException {
        try {
            DebugUtils.validateDebugSettings(debugAll, debugEvents, debugModeString);
        } catch (final DSLErrorMessageException e) {
            throw new CLIStatusException(e, e.getErrorMessage().getName(), (Object[]) e.getArgs());
        }
    }

    private void pollForLifecycleEvents(final String lifecycleEventContainerPollingID) throws InterruptedException,
			CLIException, TimeoutException, IOException {
		final RestLifecycleEventsLatch lifecycleEventsPollingLatch = this.adminFacade
				.getLifecycleEventsPollingLatch(
						lifecycleEventContainerPollingID, TIMEOUT_ERROR_MESSAGE);
		boolean isDone = false;
		boolean continuous = false;
		while (!isDone) {
			try {
				if (!continuous) {
					lifecycleEventsPollingLatch.waitForLifecycleEvents(
							getTimeoutInMinutes(), TimeUnit.MINUTES);
				} else {
					lifecycleEventsPollingLatch.continueWaitForLifecycleEvents(
							getTimeoutInMinutes(), TimeUnit.MINUTES);
				}
				isDone = true;
			} catch (final TimeoutException e) {
				if (!(Boolean) session.get(Constants.INTERACTIVE_MODE)) {
					throw e;
				}
				final boolean continueInstallation = promptWouldYouLikeToContinueQuestion();
				if (!continueInstallation) {
					throw new CLIStatusException(e,
							"service_installation_timed_out_on_client",
							serviceName);
				} else {
					continuous = true;
				}
			}
		}
	}

	private boolean promptWouldYouLikeToContinueQuestion() throws IOException {
		return ShellUtils.promptUser(session,
				"would_you_like_to_continue_service_installation", serviceName);
	}

	// TODO: THIS CODE IS COPIED AS IS FROM THE REST PROJECT
	// It is used originally in ApplicationInstallerRunnable
	// This copy is a bad idea, and should be moved out of here as soon as
	// possible.
	/**
	 * Create Properties object with settings from the service object, if found on the given service. The supported
	 * settings are: com.gs.application.dependsOn com.gs.service.type com.gs.service.icon
	 * com.gs.service.network.protocolDescription
	 * 
	 * @param service
	 *            The service object the read the settings from
	 * @return Properties object populated with the above properties, if found on the given service.
	 */
	private Properties createServiceContextProperties(final Service service) {
		final Properties contextProperties = new Properties();

		// contextProperties.setProperty("com.gs.application.services",
		// serviceNamesString);
		if (service.getDependsOn() != null) {
			contextProperties.setProperty(
					CloudifyConstants.CONTEXT_PROPERTY_DEPENDS_ON, service
							.getDependsOn().toString());
		}
		if (service.getType() != null) {
			contextProperties.setProperty(
					CloudifyConstants.CONTEXT_PROPERTY_SERVICE_TYPE,
					service.getType());
		}
		if (service.getIcon() != null) {
			contextProperties.setProperty(
					CloudifyConstants.CONTEXT_PROPERTY_SERVICE_ICON,
					CloudifyConstants.SERVICE_EXTERNAL_FOLDER
							+ service.getIcon());
		}
		if (service.getNetwork() != null) {
			if (service.getNetwork().getProtocolDescription() != null) {
				contextProperties
						.setProperty(
								CloudifyConstants.CONTEXT_PROPERTY_NETWORK_PROTOCOL_DESCRIPTION,
								service.getNetwork().getProtocolDescription());
			}
		}

		contextProperties.setProperty(
				CloudifyConstants.CONTEXT_PROPERTY_ELASTIC,
				Boolean.toString(service.isElastic()));

		if (this.debugAll) {
			contextProperties.setProperty(CloudifyConstants.CONTEXT_PROPERTY_DEBUG_ALL, Boolean.TRUE.toString());
			contextProperties.setProperty(CloudifyConstants.CONTEXT_PROPERTY_DEBUG_MODE, this.getDebugModeString());
		} else if (this.debugEvents != null) {
			contextProperties.setProperty(CloudifyConstants.CONTEXT_PROPERTY_DEBUG_EVENTS, this.debugEvents);
			contextProperties.setProperty(CloudifyConstants.CONTEXT_PROPERTY_DEBUG_MODE, this.getDebugModeString());
		}

		return contextProperties;
	}

	private File createCloudConfigurationZipFile() throws CLIStatusException,
			IOException {
		if (this.cloudConfiguration == null) {
			return null;
		}

		if (!this.cloudConfiguration.exists()) {
			throw new CLIStatusException("cloud_configuration_file_not_found",
					this.cloudConfiguration.getAbsolutePath());
		}

		// create a temp file in a temp directory
		final File tempDir = File.createTempFile(
				"__Cloudify_Cloud_configuration", ".tmp");
		FileUtils.forceDelete(tempDir);
		tempDir.mkdirs();

		final File tempFile = new File(tempDir,
				CloudifyConstants.SERVICE_CLOUD_CONFIGURATION_FILE_NAME);

		// mark files for deletion on JVM exit
		tempFile.deleteOnExit();
		tempDir.deleteOnExit();

		if (this.cloudConfiguration.isDirectory()) {
			ZipUtils.zip(this.cloudConfiguration, tempFile);
		} else if (this.cloudConfiguration.isFile()) {
			ZipUtils.zipSingleFile(this.cloudConfiguration, tempFile);
		} else {
			throw new IOException(this.cloudConfiguration
					+ " is neither a file nor a directory");
		}

		return tempFile;
	}

	public File getCloudConfiguration() {
		return cloudConfiguration;
	}

	public void setCloudConfiguration(final File cloudConfiguration) {
		this.cloudConfiguration = cloudConfiguration;
	}

	public int getTimeoutInMinutes() {
		return timeoutInMinutes;
	}

	public void setTimeoutInMinutes(final int timeoutInMinutes) {
		this.timeoutInMinutes = timeoutInMinutes;
	}

	public boolean isDisableSelfHealing() {
		return disableSelfHealing;
	}

	public void setDisableSelfHealing(final boolean disableSelfHealing) {
		this.disableSelfHealing = disableSelfHealing;
	}

	public boolean isDebugAll() {
		return debugAll;
	}

	public void setDebugAll(final boolean debugAll) {
		this.debugAll = debugAll;
	}

	public String getDebugEvents() {
		return debugEvents;
	}

	public void setDebugEvents(final String debugEvents) {
		this.debugEvents = debugEvents;
	}

	public String getDebugModeString() {
		return debugModeString;
	}

	public void setDebugModeString(final String debugModeString) {
		this.debugModeString = debugModeString;
	}

	public String getServiceFileName() {
		return serviceFileName;
	}

	public void setServiceFileName(final String serviceFileName) {
		this.serviceFileName = serviceFileName;
	}

}
