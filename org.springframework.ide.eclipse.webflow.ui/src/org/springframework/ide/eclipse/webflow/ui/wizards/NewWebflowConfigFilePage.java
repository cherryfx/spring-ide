/*******************************************************************************
 * Copyright (c) 2005, 2007 Spring IDE Developers
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Spring IDE Developers - initial API and implementation
 *******************************************************************************/
package org.springframework.ide.eclipse.webflow.ui.wizards;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;

import org.eclipse.core.runtime.Preferences;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.dialogs.WizardNewFileCreationPage;
import org.eclipse.wst.sse.core.internal.encoding.CommonEncodingPreferenceNames;
import org.eclipse.wst.xml.core.internal.XMLCorePlugin;

/**
 * @author Christian Dupuis
 * @since 2.0
 */
@SuppressWarnings("restriction")
public class NewWebflowConfigFilePage extends WizardNewFileCreationPage {

	private Button swf2Button;

	public NewWebflowConfigFilePage(String pageName, IStructuredSelection selection) {
		super(pageName, selection);
		setTitle("New Web Flow Definition file");
		setDescription("Select the location and give a name for the new Spring Web Flow Definition file");
	}

	protected InputStream getInitialContents() {
		try {
			return createXMLDocument();
		}
		catch (Exception e) {
		}
		return null;
	}

	protected InputStream createXMLDocument() throws Exception {
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		String charSet = getUserPreferredCharset();

		PrintWriter writer = new PrintWriter(new OutputStreamWriter(outputStream, charSet));
		writer.println("<?xml version=\"1.0\" encoding=\"" + charSet + "\"?>"); //$NON-NLS-1$ //$NON-NLS-2$
		StringBuilder builder = new StringBuilder().append(
				"<flow xmlns=\"http://www.springframework.org/schema/webflow\"\r\n").append(
				"	xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\r\n").append(
				"	xsi:schemaLocation=\"http://www.springframework.org/schema/webflow\r\n");
		if (swf2Button.getSelection()) {
			builder
					.append("        http://www.springframework.org/schema/webflow/spring-webflow-2.0.xsd\">");
		}
		else {
			builder
					.append("        http://www.springframework.org/schema/webflow/spring-webflow-1.0.xsd\">");
		}
		builder.append("\r\n").append("\r\n");
		if (!swf2Button.getSelection()) {
			builder.append("	<start-state idref=\"start\" />\r\n").append("\r\n");
		}
		builder.append("	<view-state id=\"start\">\r\n").append("	</view-state>\r\n")
				.append("\r\n").append("</flow>");
		writer.write(builder.toString());
		writer.flush();
		outputStream.close();

		ByteArrayInputStream inputStream = new ByteArrayInputStream(outputStream.toByteArray());
		return inputStream;
	}

	private String getUserPreferredCharset() {
		Preferences preference = XMLCorePlugin.getDefault().getPluginPreferences();
		String charSet = preference.getString(CommonEncodingPreferenceNames.OUTPUT_CODESET);
		return charSet;
	}

	@Override
	protected void createAdvancedControls(Composite parent) {
		super.createAdvancedControls(parent);
		swf2Button = new Button(parent, SWT.CHECK);
		swf2Button
				.setText("Use Spring Web Flow 2 flow definition syntax (Required if you want to use Spring Web Flow 2)");
		swf2Button.setSelection(true);
	}
}
