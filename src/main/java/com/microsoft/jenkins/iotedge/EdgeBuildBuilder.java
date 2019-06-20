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
import com.microsoft.jenkins.iotedge.model.DockerCredential;
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

public class EdgeBuildBuilder extends BaseBuilder {

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
    public EdgeBuildBuilder() {
        super();
    }

    @Override
    public void perform(Run<?, ?> run, FilePath workspace, Launcher launcher, TaskListener listener) throws InterruptedException, IOException {
        try {
            // Generate .env file for iotedgedev use
            writeEnvFile(Paths.get(workspace.getRemote(), Constants.IOTEDGEDEV_ENV_FILENAME).toString(), "", "", "", "");

            ShellExecuter executer = new ShellExecuter(run, launcher, listener, new File(workspace.getRemote()));
            Map<String, String> envs = new HashMap<>();
            executer.executeAZ(String.format("iotedgedev build --file \"%s\" --platform %s", deploymentManifestFilePath, defaultPlatform), true, envs);

            AzureIoTEdgePlugin.sendEvent(run.getClass().getSimpleName(), Constants.TELEMETRY_VALUE_TASK_TYPE_BUILD, null, run.getFullDisplayName(), null, null);
        } catch (AzureCloudException e) {
            AzureIoTEdgePlugin.sendEvent(run.getClass().getSimpleName(), Constants.TELEMETRY_VALUE_TASK_TYPE_BUILD, e.getMessage(), run.getFullDisplayName(), null, null);
            throw new AbortException(e.getMessage());
        }
    }

    @Extension
    @Symbol("azureIoTEdgeBuild")
    public static final class DescriptorImpl extends BaseBuilder.DescriptorImpl {

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Azure IoT Edge Build";
        }

    }

}
