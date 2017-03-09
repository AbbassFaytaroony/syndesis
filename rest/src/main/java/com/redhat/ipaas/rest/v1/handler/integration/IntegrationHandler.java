/**
 * Copyright (C) 2016 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.redhat.ipaas.rest.v1.handler.integration;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.ws.rs.Path;

import com.redhat.ipaas.core.IPaasServerException;
import com.redhat.ipaas.dao.manager.DataManager;
import com.redhat.ipaas.github.GitHubService;
import com.redhat.ipaas.model.integration.Integration;
import com.redhat.ipaas.project.converter.IntegrationToProjectConverter;
import com.redhat.ipaas.rest.v1.handler.BaseHandler;
import com.redhat.ipaas.rest.v1.operations.*;
import io.swagger.annotations.Api;
import org.springframework.stereotype.Component;

@Path("/integrations")
@Api(value = "integrations")
@Component
public class IntegrationHandler extends BaseHandler implements Lister<Integration>, Getter<Integration>, Creator<Integration>, Deleter<Integration>, Updater<Integration> {

    private final GitHubService gitHubService;

    private final IntegrationToProjectConverter projectConverter;

    public IntegrationHandler(DataManager dataMgr, GitHubService gitHubService, IntegrationToProjectConverter projectConverter) {
        super(dataMgr);
        this.gitHubService = gitHubService;
        this.projectConverter = projectConverter;
    }

    @Override
    public Class<Integration> resourceClass() {
        return Integration.class;
    }

    @Override
    public String resourceKind() {
        return Integration.KIND;
    }

    @Override
    public Integration create(Integration integration) {
        ensureGitHubSetup(integration);
        return Creator.super.create(integration);
    }

    // ==========================================================================

    private void ensureGitHubSetup(Integration integration) {
        try {
            integration = ensureGitRepoName(integration);
            String repoName = integration.getGitRepo().orElseThrow(() -> new IllegalArgumentException("Missing git repo in integration"));

            if (gitHubService.createRepositoryIfMissing(repoName)) {
                setupBuild(repoName);
            }

            updateProjectFiles(repoName, integration);

        } catch (IOException e) {
            throw IPaasServerException.launderThrowable(e);
        }
    }

    private void updateProjectFiles(String repoName, Integration integration) throws IOException {
        Map<String, byte[]> fileContents = projectConverter.convert(integration);
        gitHubService.createOrUpdate(repoName, generateCommitMessage(), fileContents);
    }

    private Integration ensureGitRepoName(Integration integration) {
        Optional<String> repoNameOptional = integration.getGitRepo();
        if (!repoNameOptional.isPresent()) {
            String generatedRepoName = gitHubService.sanitizeRepoName(integration.getName());
            integration = new Integration.Builder().createFrom(integration).gitRepo(generatedRepoName).build();
        }
        return integration;
    }

    private String generateCommitMessage() {
        // TODO Let's generate some nice message...
        return "Updated";
    }

    private void setupBuild(String repoName) throws IOException {
        String secret = createSecret();
        gitHubService.buildTriggerAsWebHook(repoName, repoName /* use repo as buildconfig name for now */, secret);
    }

    private String createSecret() {
        return UUID.randomUUID().toString();
    }
}
