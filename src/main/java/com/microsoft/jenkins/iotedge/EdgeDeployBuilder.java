/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.iotedge;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.containerregistry.AccessKeyType;
import com.microsoft.azure.management.containerregistry.Registries;
import com.microsoft.azure.management.containerregistry.Registry;
import com.microsoft.azure.management.containerregistry.RegistryCredentials;
import com.microsoft.azure.util.AzureCredentials;
import com.microsoft.jenkins.iotedge.model.AzureCloudException;
import com.microsoft.jenkins.iotedge.model.AzureCredentialCache;
import com.microsoft.jenkins.iotedge.model.AzureCredentialsValidationException;
import com.microsoft.jenkins.iotedge.util.AzureUtils;
import com.microsoft.jenkins.iotedge.util.Constants;
import com.microsoft.jenkins.iotedge.util.Util;
import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.*;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.docker.commons.credentials.DockerRegistryEndpoint;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import javax.ws.rs.POST;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EdgeDeployBuilder extends BaseBuilder {

    public String getDeploymentFilePath() {
        return deploymentFilePath;
    }

    @DataBoundSetter
    public void setDeploymentFilePath(String deploymentFilePath) {
        this.deploymentFilePath = deploymentFilePath;
    }

    private String deploymentFilePath;

    public String getIothubName() {
        return iothubName;
    }

    @DataBoundSetter
    public void setIothubName(String iothubName) {
        this.iothubName = iothubName;
    }

    public String getDeploymentType() {
        return deploymentType;
    }

    @DataBoundSetter
    public void setDeploymentType(String deploymentType) {
        this.deploymentType = deploymentType;
    }

    public String getDeviceId() {
        return deviceId;
    }

    @DataBoundSetter
    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getTargetCondition() {
        return targetCondition;
    }

    @DataBoundSetter
    public void setTargetCondition(String targetCondition) {
        this.targetCondition = targetCondition;
    }

    public String getDeploymentId() {
        return deploymentId;
    }

    @DataBoundSetter
    public void setDeploymentId(String deploymentId) {
        this.deploymentId = deploymentId;
    }

    public String getPriority() {
        return priority;
    }

    @DataBoundSetter
    public void setPriority(String priority) {
        this.priority = priority;
    }

    private String iothubName;
    private String deploymentType;
    private String deviceId;
    private String targetCondition;

    private String deploymentId;
    private String priority;

    @DataBoundConstructor
    public EdgeDeployBuilder(final String azureCredentialsId,
                             final String resourceGroup,
                             final String rootPath) {
        super(azureCredentialsId, resourceGroup, rootPath);
    }

    public EdgeDeployBuilder(final String azureCredentialsId,
                             final String resourceGroup) {
        super(azureCredentialsId, resourceGroup);
    }

    @Override
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
            value="DLS_DEAD_LOCAL_STORE",
            justification="Don't need use the result from delete file")
    public void perform(Run<?, ?> run, FilePath workspace, Launcher launcher, TaskListener listener) throws InterruptedException, IOException {
        try {
            // Generate .env file for iotedgedev use
            writeEnvFile(Paths.get(workspace.getRemote(), Constants.IOTEDGEDEV_ENV_FILENAME).toString(), "", "", "", "");
            
            String deploymentJsonPath = Paths.get(workspace.getRemote(), deploymentFilePath).toString();

            // Modify deployment.json structure
            InputStream stream = new FileInputStream(deploymentJsonPath);
            JSONObject deploymentJson = new JSONObject(IOUtils.toString(stream, Constants.CHARSET_UTF_8));
            stream.close();

            // deploy using azure cli
            String condition = "";
            if (deploymentType.equals("multiple")) {
                condition = targetCondition;
            } else {
                condition = "deviceId='" + deviceId + "'";
            }
            AzureCredentials.ServicePrincipal servicePrincipal = AzureCredentials.getServicePrincipal(getAzureCredentialsId());
            AzureCredentialCache credentialCache = new AzureCredentialCache(servicePrincipal);
            ShellExecuter azExecuter = new ShellExecuter(run, launcher, listener, new File(workspace.getRemote()));
            try {
                azExecuter.login(credentialCache);

                String scriptToDelete = "az iot edge deployment delete --hub-name " + iothubName + " --config-id " + deploymentId + "";
                azExecuter.executeAZ(scriptToDelete, false);
            } catch (AzureCloudException e) {
                if (!e.getMessage().contains("ConfigurationNotFound")) {
                    throw e;
                }
            }

            String scriptToDeploy = "az iot edge deployment create --config-id " + deploymentId + " --hub-name " + iothubName + " --content \"" + deploymentJsonPath + "\" --target-condition \"" + condition + "\" --priority " + priority + "";
            azExecuter.executeAZ(scriptToDeploy, true);

            // delete generated deployment.json
            // Files.deleteIfExists(Paths.get(workspace.getRemote(), Constants.EDGE_DEPLOYMENT_CONFIG_FOLDERNAME, Constants.EDGE_DEPLOYMENT_CONFIG_FILENAME));
            AzureIoTEdgePlugin.sendEvent(run.getClass().getSimpleName(), Constants.TELEMETRY_VALUE_TASK_TYPE_DEPLOY, null, run.getFullDisplayName(), servicePrincipal.getSubscriptionId() , String.format(Constants.IOT_HUB_URL, iothubName));
        } catch (AzureCloudException | AzureCredentialsValidationException e) {
            AzureIoTEdgePlugin.sendEvent(run.getClass().getSimpleName(), Constants.TELEMETRY_VALUE_TASK_TYPE_DEPLOY, e.getMessage(), run.getFullDisplayName(), AzureCredentials.getServicePrincipal(getAzureCredentialsId()).getSubscriptionId(), String.format(Constants.IOT_HUB_URL, iothubName));
            throw new AbortException(e.getMessage());
        }
    }

    @Extension
    @Symbol("azureIoTEdgeDeploy")
    public static final class DescriptorImpl extends BaseBuilder.DescriptorImpl {
        public static final String defaultPriority = "10";

        @POST
        public FormValidation doCheckTargetCondition(@QueryParameter String value)
                throws IOException, ServletException {
            Jenkins.getInstance().checkPermission(Jenkins.ADMINISTER);
            if (Util.isValidTargetCondition(value)) {
                return FormValidation.ok();
            } else {
                return FormValidation.error("Target condition is not in right format. Click help button to learn more.");
            }
        }

        @POST
        public FormValidation doCheckPriority(@QueryParameter String value)
                throws IOException, ServletException {
            Jenkins.getInstance().checkPermission(Jenkins.ADMINISTER);
            if (Util.isValidPriority(value)) {
                return FormValidation.ok();
            } else {
                return FormValidation.error("Priority is not in right format. Click help button to learn more.");
            }
        }

        @POST
        public FormValidation doCheckDeploymentId(@QueryParameter String value)
                throws IOException, ServletException {
            Jenkins.getInstance().checkPermission(Jenkins.ADMINISTER);
            if (Util.isValidDeploymentId(value)) {
                return FormValidation.ok();
            } else {
                return FormValidation.error("Deployment ID is not in right format. Click help button to learn more.");
            }
        }

        public DockerRegistryEndpoint.DescriptorImpl getDockerRegistryEndpointDescriptor() {
            final Jenkins jenkins = Jenkins.getInstance();
            if (jenkins != null) {
                return (DockerRegistryEndpoint.DescriptorImpl)
                        jenkins.getDescriptor(DockerRegistryEndpoint.class);
            } else {
                return null;
            }
        }

        @POST
        public ListBoxModel doFillAzureCredentialsIdItems(@AncestorInPath final Item owner) {
            Jenkins.getInstance().checkPermission(Jenkins.ADMINISTER);
            return listAzureCredentialsIdItems(owner);
        }

        @POST
        public ListBoxModel doFillResourceGroupItems(@AncestorInPath Item owner,
                                                     @QueryParameter String azureCredentialsId) {
            Jenkins.getInstance().checkPermission(Jenkins.ADMINISTER);
            return listResourceGroupItems(owner, azureCredentialsId);
        }

        @POST
        public ListBoxModel doFillIothubNameItems(@AncestorInPath Item owner,
                                                  @QueryParameter String azureCredentialsId,
                                                  @QueryParameter String resourceGroup) {
            Jenkins.getInstance().checkPermission(Jenkins.ADMINISTER);
            if (StringUtils.isNotBlank(azureCredentialsId) && StringUtils.isNotBlank(resourceGroup)) {
                return listIothubNameItems(owner, azureCredentialsId, resourceGroup);
            } else {
                return new ListBoxModel(new ListBoxModel.Option(Constants.EMPTY_SELECTION, ""));
            }
        }

        @POST
        public ListBoxModel doFillDeviceIdItems(@AncestorInPath Item owner,
                                                @QueryParameter String azureCredentialsId,
                                                @QueryParameter String resourceGroup,
                                                @QueryParameter String iothubName) {
            Jenkins.getInstance().checkPermission(Jenkins.ADMINISTER);
            if (StringUtils.isNotBlank(azureCredentialsId) && StringUtils.isNotBlank(resourceGroup)&& StringUtils.isNotBlank(iothubName)) {
                return listDeviceIdItems(owner, azureCredentialsId, resourceGroup, iothubName);
            } else {
                return new ListBoxModel(new ListBoxModel.Option(Constants.EMPTY_SELECTION, ""));
            }
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Azure IoT Edge Deploy";
        }

    }

}
