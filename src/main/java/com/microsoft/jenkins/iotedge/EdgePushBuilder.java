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
import com.microsoft.azure.management.containerregistry.implementation.ContainerRegistryManager;
import com.microsoft.jenkins.iotedge.model.AzureCloudException;
import com.microsoft.jenkins.iotedge.util.AzureUtils;
import com.microsoft.jenkins.iotedge.util.Constants;
import com.microsoft.jenkins.iotedge.util.Env;
import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.docker.commons.credentials.DockerRegistryEndpoint;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import javax.ws.rs.POST;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public class EdgePushBuilder extends BaseBuilder {

    public String getDockerRegistryType() {
        return dockerRegistryType;
    }

    @DataBoundSetter
    public void setDockerRegistryType(String dockerRegistryType) {
        this.dockerRegistryType = dockerRegistryType;
    }

    public DockerRegistryEndpoint getDockerRegistryEndpoint() {
        return dockerRegistryEndpoint;
    }

    @DataBoundSetter
    public void setDockerRegistryEndpoint(DockerRegistryEndpoint dockerRegistryEndpoint) {
        this.dockerRegistryEndpoint = dockerRegistryEndpoint;
    }

    public String getAcrName() {
        return acrName;
    }

    @DataBoundSetter
    public void setAcrName(String acrName) {
        this.acrName = acrName;
    }

    public String getBypassModules() {
        return bypassModules;
    }

    @DataBoundSetter
    public void setBypassModules(String bypassModules) {
        this.bypassModules = bypassModules;
    }

    private String bypassModules = DescriptorImpl.defaultModulesToBuild;

    private String dockerRegistryType;

    private String acrName;

    private DockerRegistryEndpoint dockerRegistryEndpoint;

    public String getDeploymentManifestFilePath() {
        return deploymentManifestFilePath;
    }

    @DataBoundSetter
    public void setDeploymentManifestFilePath(String deploymentManifestFilePath) {
        this.deploymentManifestFilePath = deploymentManifestFilePath;
    }

    private String deploymentManifestFilePath;

    public String getDefaultPlatform() {
        return defaultPlatform;
    }

    @DataBoundSetter
    public void setDefaultPlatform(String defaultPlatform) {
        this.defaultPlatform = defaultPlatform;
    }

    private String defaultPlatform;

    @DataBoundConstructor
    public EdgePushBuilder(final String azureCredentialsId,
                           final String resourceGroup) {
        super(azureCredentialsId, resourceGroup);
        this.dockerRegistryType = Constants.DOCKER_REGISTRY_TYPE_ACR;
    }

    @Override
    public void perform(Run<?, ?> run, FilePath workspace, Launcher launcher, TaskListener listener) throws InterruptedException, IOException {
        try {
            boolean isAcr = dockerRegistryType.equals(Constants.DOCKER_REGISTRY_TYPE_ACR);
            String credentialId = null;
            String url = "", username = "", password = "";

            if (isAcr) {
                credentialId = getAzureCredentialsId();
                final Azure azureClient = AzureUtils.buildClient(run.getParent(), credentialId);
                Registries rs = azureClient.containerRegistries();
                Registry r = rs.getByResourceGroup(getResourceGroup(), acrName);
                RegistryCredentials rc = r.getCredentials();
                username = rc.username();
                url = r.loginServerUrl();
                password = rc.accessKeys().get(AccessKeyType.PRIMARY);
            } else {
                url = dockerRegistryEndpoint.getUrl();
                credentialId = dockerRegistryEndpoint.getCredentialsId();
                StandardUsernamePasswordCredentials credential = CredentialsProvider.findCredentialById(credentialId, StandardUsernamePasswordCredentials.class, run);
                if (credential != null) {
                    username = credential.getUsername();
                    password = credential.getPassword().getPlainText();
                }
            }

            // Generate .env file for iotedgedev use
            writeEnvFile(Paths.get(workspace.getRemote(), Constants.IOTEDGEDEV_ENV_FILENAME).toString(), url, bypassModules, "", "");

            ShellExecuter executer = new ShellExecuter(run, launcher, listener, new File(workspace.getRemote()));
            Map<String, String> envs = new HashMap<>();
            envs.put(Constants.IOTEDGEDEV_ENV_REGISTRY_USERNAME, username);
            envs.put(Constants.IOTEDGEDEV_ENV_REGISTRY_PASSWORD, password);
            executer.executeAZ(String.format("iotedgedev push --no-build --file \"%s\" --platform %s", deploymentManifestFilePath, defaultPlatform), true, envs);

            AzureIoTEdgePlugin.sendEvent(run.getClass().getSimpleName(), Constants.TELEMETRY_VALUE_TASK_TYPE_PUSH, null, run.getFullDisplayName(), null, null);
        } catch (AzureCloudException e) {
            AzureIoTEdgePlugin.sendEvent(run.getClass().getSimpleName(), Constants.TELEMETRY_VALUE_TASK_TYPE_PUSH, e.getMessage(), run.getFullDisplayName(), null, null);
            throw new AbortException(e.getMessage());
        }
    }

    @Extension
    @Symbol("azureIoTEdgePush")
    public static final class DescriptorImpl extends BaseBuilder.DescriptorImpl {

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
        public ListBoxModel doFillAcrNameItems(@AncestorInPath Item owner,
                                               @QueryParameter String azureCredentialsId,
                                               @QueryParameter String resourceGroup) {
            if (StringUtils.isNotBlank(azureCredentialsId) && StringUtils.isNotBlank(resourceGroup)) {
                Jenkins.getInstance().checkPermission(Jenkins.ADMINISTER);
                return listAcrNameItems(owner, azureCredentialsId, resourceGroup);
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
            return "Azure IoT Edge Push";
        }

    }

}
