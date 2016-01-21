/*******************************************************************************
 * Copyright (c) 2016 Pivotal, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Pivotal, Inc. - initial API and implementation
 *******************************************************************************/
package org.springframework.ide.eclipse.boot.dash.cloudfoundry.ops;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cloudfoundry.client.lib.domain.CloudApplication;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.springframework.ide.eclipse.boot.dash.cloudfoundry.CloudAppInstances;
import org.springframework.ide.eclipse.boot.dash.cloudfoundry.CloudFoundryBootDashModel;
import org.springframework.ide.eclipse.boot.dash.model.UserInteractions;

/**
 * Operation for refreshing existing cloud applications.
 *
 * @author Alex Boyko
 *
 */
public class RefreshApplications extends CloudOperation {

	private Collection<CloudApplication> apps;
	private UserInteractions ui;

	public RefreshApplications(CloudFoundryBootDashModel model, Collection<CloudApplication> apps, UserInteractions ui) {
		super("Refreshing applications", model);
		this.apps = apps;
		this.ui = ui;
	}

	@Override
	protected void doCloudOp(IProgressMonitor monitor) throws Exception, OperationCanceledException {
		if (apps != null && !apps.isEmpty()) {
			Map<CloudAppInstances, IProject> updatedApplications = new HashMap<CloudAppInstances, IProject>();
			Map<String, String> existingProjectToAppMappings = this.model.getProjectToAppMappingStore().getMapping();
			List<CloudApplication> toUpdateStats = new ArrayList<>();

			for (CloudAppInstances instances : model.getAppCache().getAppInstances()) {
				String projectName = existingProjectToAppMappings.get(instances.getApplication().getName());
				IProject project = null;
				if (projectName != null) {
					project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
					if (project == null || !project.isAccessible()) {
						project = null;
					}
				}
				if (apps.contains(instances.getApplication())) {
					CloudApplication newApplication = model.getRunTarget().getClientRequests()
							.getApplication(instances.getApplication().getName());
					updatedApplications.put(new CloudAppInstances(newApplication, instances.getStats()), project);
					toUpdateStats.add(newApplication);
				} else {
					updatedApplications.put(instances, project);
				}
			}

			model.updateElements(updatedApplications);

			model.getOperationsExecution(ui).runOpAsynch(new AppInstancesRefreshOperation(model, toUpdateStats));
		}
	}

}
