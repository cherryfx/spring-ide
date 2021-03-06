/*******************************************************************************
 * Copyright (c) 2015, 2016 Pivotal, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Pivotal, Inc. - initial API and implementation
 *******************************************************************************/
package org.springframework.ide.eclipse.boot.properties.editor.yaml;

import org.dadacoalition.yedit.editor.YEditSourceViewerConfiguration;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.springframework.ide.eclipse.boot.properties.editor.SpringPropertiesEditorPlugin;
import org.springframework.ide.eclipse.boot.properties.editor.util.Listener;
import org.springframework.ide.eclipse.boot.properties.editor.util.SpringPropertiesIndexManager;
import org.springframework.ide.eclipse.editor.support.preferences.ProblemSeverityPreferencesUtil;
import org.springframework.ide.eclipse.editor.support.yaml.AbstractYamlEditor;

public class SpringYamlEditor extends AbstractYamlEditor implements Listener<SpringPropertiesIndexManager>, IPropertyChangeListener {

	private ApplicationYamlSourceViewerConfiguration sourceViewerConf;

	@Override
	protected YEditSourceViewerConfiguration createSourceViewerConfiguration() {
		return this.sourceViewerConf = new ApplicationYamlSourceViewerConfiguration(this);
	}

	@Override
	protected void initializeEditor() {
		super.initializeEditor();
		SpringPropertiesEditorPlugin.getIndexManager().addListener(this);
		SpringPropertiesEditorPlugin.getDefault().getPreferenceStore().addPropertyChangeListener(this);
	}

	@Override
	public void changed(SpringPropertiesIndexManager info) {
		if (sourceViewerConf!=null) {
			sourceViewerConf.forceReconcile();
		}
	}

	@Override
	public void dispose() {
		super.dispose();
		SpringPropertiesEditorPlugin.getIndexManager().removeListener(this);
		SpringPropertiesEditorPlugin.getDefault().getPreferenceStore().removePropertyChangeListener(this);
	}

	@Override
	public void propertyChange(PropertyChangeEvent event) {
		if (event.getProperty().startsWith(ProblemSeverityPreferencesUtil.PREFERENCE_PREFIX)) {
			if (sourceViewerConf!=null) {
				sourceViewerConf.forceReconcile();
			}
		}
	}
}
