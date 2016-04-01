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
package org.springframework.ide.eclipse.boot.dash.cloudfoundry.deployment;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.reconciler.DirtyRegion;
import org.eclipse.jface.text.reconciler.IReconcilingStrategy;
import org.eclipse.jface.text.reconciler.IReconcilingStrategyExtension;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.IAnnotationModelExtension;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.ISourceViewerExtension2;
import org.springframework.ide.eclipse.boot.dash.BootDashActivator;
import org.springframework.ide.eclipse.boot.dash.cloudfoundry.ApplicationManifestHandler;
import org.springframework.ide.eclipse.editor.support.yaml.ast.YamlASTProvider;
import org.springframework.ide.eclipse.editor.support.yaml.ast.YamlFileAST;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

/**
 * Reconciling strategy responsible for keeping track of application name
 * annotations
 *
 * @author Alex Boyko
 *
 */
public class AppNameReconcilingStrategy implements IReconcilingStrategy, IReconcilingStrategyExtension {

	/**
	 * Source viewer
	 */
	private ISourceViewer fViewer;

	/**
	 * YAML parser
	 */
	private YamlASTProvider fParser;

	/**
	 * Constant application name. If not <code>null</code> then corresponding annotation must be selected
	 */
	final private String fAppName;

	/**
	 * Document to perform reconciling on
	 */
	private IDocument fDocument;

	/**
	 * Reconciling cycle progress monitor
	 */
	private IProgressMonitor fProgressMonitor;

	/**
	 * Creates new instance of the reconciler
	 *
	 * @param viewer Source viewer
	 * @param parser YAML parser
	 * @param appName Application name to keep selected all the time
	 */
	public AppNameReconcilingStrategy(ISourceViewer viewer, YamlASTProvider parser, String appName) {
		fViewer = viewer;
		fParser = parser;
		fAppName = appName;
	}

	/*
	 * @see org.eclipse.jface.text.reconciler.IReconcilingStrategyExtension#initialReconcile()
	 */
	public void initialReconcile() {
		reconcile(new Region(0, fDocument.getLength()));
	}

	/*
	 * @see org.eclipse.jface.text.reconciler.IReconcilingStrategy#reconcile(org.eclipse.jface.text.reconciler.DirtyRegion,org.eclipse.jface.text.IRegion)
	 */
	public void reconcile(DirtyRegion dirtyRegion, IRegion subRegion) {
		try {
			IRegion startLineInfo= fDocument.getLineInformationOfOffset(subRegion.getOffset());
			IRegion endLineInfo= fDocument.getLineInformationOfOffset(subRegion.getOffset() + Math.max(0, subRegion.getLength() - 1));
			if (startLineInfo.getOffset() == endLineInfo.getOffset())
				subRegion= startLineInfo;
			else
				subRegion= new Region(startLineInfo.getOffset(), endLineInfo.getOffset() + Math.max(0, endLineInfo.getLength() - 1) - startLineInfo.getOffset());

		} catch (BadLocationException e) {
			subRegion= new Region(0, fDocument.getLength());
		}
		reconcile(subRegion);
	}

	private AppNameAnnotationModel getAppNameAnnotationModel() {
		IAnnotationModel model = fViewer instanceof ISourceViewerExtension2 ? ((ISourceViewerExtension2)fViewer).getVisualAnnotationModel() : fViewer.getAnnotationModel();
		if (model instanceof IAnnotationModelExtension) {
			return (AppNameAnnotationModel) ((IAnnotationModelExtension) model).getAnnotationModel(AppNameAnnotationModel.APP_NAME_MODEL_KEY);
		}
		return (AppNameAnnotationModel) model;
	}

	@Override
	public void reconcile(IRegion region) {
		AppNameAnnotationModel annotationModel = getAppNameAnnotationModel();

		if (annotationModel == null) {
			return;
		}

		List<Annotation> toRemove= new ArrayList<Annotation>();

		@SuppressWarnings("unchecked")
		Iterator<? extends Annotation> iter= annotationModel.getAnnotationIterator();
		while (iter.hasNext()) {
			Annotation annotation= iter.next();
			if (AppNameAnnotation.TYPE.equals(annotation.getType())) {
				toRemove.add(annotation);
			}
		}
		Annotation[] annotationsToRemove= toRemove.toArray(new Annotation[toRemove.size()]);

		/*
		 * Create brand new annotation to position map based on docs contents
		 */
		Map<AppNameAnnotation, Position> annotationsToAdd = createAnnotations(annotationModel);

		/*
		 * Update annotation model
		 */
		if (annotationModel instanceof IAnnotationModelExtension)
			((IAnnotationModelExtension)annotationModel).replaceAnnotations(annotationsToRemove, annotationsToAdd);
		else {
			for (int i= 0; i < annotationsToRemove.length; i++)
				annotationModel.removeAnnotation(annotationsToRemove[i]);
			for (iter= annotationsToAdd.keySet().iterator(); iter.hasNext();) {
				Annotation annotation= iter.next();
				annotationModel.addAnnotation(annotation, annotationsToAdd.get(annotation));
			}
		}

	}

	/*
	 * @see org.eclipse.jface.text.reconciler.IReconcilingStrategyExtension#setProgressMonitor(org.eclipse.core.runtime.IProgressMonitor)
	 */
	public final void setProgressMonitor(IProgressMonitor monitor) {
		fProgressMonitor= monitor;
	}

	@Override
	public void setDocument(IDocument document) {
		fDocument= document;
	}

	/**
	 * Create new annotation to position mapping based on the document contents
	 *
	 * @param annotationModel Application name annotations model
	 * @return Map of annotations to their corresponding positions
	 */
	private Map<AppNameAnnotation, Position> createAnnotations(AppNameAnnotationModel annotationModel) {
		Map<AppNameAnnotation, Position> annotationsMap = new LinkedHashMap<>();
		fProgressMonitor.beginTask("Calculating application names", 100);
		try {
			YamlFileAST ast = fParser.getAST(fDocument);
			List<Node> rootList = ast.getNodes();
			fProgressMonitor.worked(70);
			if (rootList.size() == 1) {
				Node root = rootList.get(0);
				SequenceNode applicationsNode = YamlGraphDeploymentProperties.getNode(root, ApplicationManifestHandler.APPLICATIONS_PROP, SequenceNode.class);
				if (applicationsNode == null) {
					/*
					 * No 'applications' YAML node consider root elements to the deployment properties of an application
					 */
					ScalarNode node = YamlGraphDeploymentProperties.getPropertyValue(root, ApplicationManifestHandler.NAME_PROP, ScalarNode.class);
					if (node != null) {
						/*
						 * There is 'name' property present, so yes root has application deployment props
						 */
						annotationsMap.put(new AppNameAnnotation(node.getValue(), true),
								new Position(root.getStartMark().getIndex(),
										getLastWhiteCharIndex(fDocument.get(), root.getEndMark().getIndex())
												- root.getStartMark().getIndex()));
					}
				} else {
					/*
					 * Go through entries in the 'applications' sequence node
					 */
					for (Node appNode : applicationsNode.getValue()) {
						ScalarNode node = YamlGraphDeploymentProperties.getNode(appNode, ApplicationManifestHandler.NAME_PROP, ScalarNode.class);
						if (node != null) {
							/*
							 * Add application name annotation entry
							 */
							annotationsMap.put(new AppNameAnnotation(node.getValue()), new Position(appNode.getStartMark().getIndex(), getLastWhiteCharIndex(fDocument.get(), appNode.getEndMark().getIndex()) - appNode.getStartMark().getIndex()));
						}
					}
				}
				fProgressMonitor.worked(20);
				if (!annotationsMap.isEmpty()) {
					if (fAppName == null) {
						/*
						 * Select either previously selected app name annotation or the first found
						 */
						reselectAnnotation(annotationModel, annotationsMap);
					} else {
						/*
						 * Select annotation corresponding to application name == to fAppName
						 */
						selectAnnotationByAppName(annotationsMap);
					}
					fProgressMonitor.worked(10);
				}
			}
		} catch (Throwable t) {
			BootDashActivator.log(t);
		} finally {
			fProgressMonitor.done();
		}
		return annotationsMap;
	}

	/**
	 * Selects annotation from the map corresponding to currently selected
	 * annotation. Otherwise just selects the first found annotation
	 *
	 * @param annotationModel Application name annotations model
	 * @param annotationsMap Map of application name annotations to positions
	 */
	private void reselectAnnotation(AppNameAnnotationModel annotationModel, Map<AppNameAnnotation, Position> annotationsMap) {
		AppNameAnnotation selected = annotationModel.getSelectedAppAnnotation();
		Map.Entry<AppNameAnnotation, Position> newSelected = null;
		if (selected != null) {
			Position selectedPosition = annotationModel.getPosition(selected);
			for (Map.Entry<AppNameAnnotation, Position> entry : annotationsMap.entrySet()) {
				/*
				 * Check if application name matches
				 */
				if (entry.getKey().getText().equals(selected.getText())) {
					/*
					 * If name matches see if previous match is further away
					 * from previously selected annotation offset than the
					 * current match. Update the match accordingly.
					 */
					if (newSelected == null) {
						newSelected = entry;
					} else if (Math.abs(newSelected.getValue().getOffset() - selectedPosition.getOffset()) > Math.abs(entry.getValue().getOffset() - selectedPosition.getOffset())){
						newSelected = entry;
					}
				} else if (entry.getValue().getOffset() == selectedPosition.getOffset() && newSelected == null) {
					newSelected = entry;
				}
			}
		}
		if (newSelected == null) {
			/*
			 * No matches found. Select the first annotation to have something selected.
			 */
			newSelected = annotationsMap.entrySet().iterator().next();
		}
		newSelected.getKey().markSelected();
	}

	/**
	 * Select annotation matching constant application name, i.e. <code>FAppName</code>
	 *
	 * @param annotationsMap Map of application name annotations to positions
	 */
	private void selectAnnotationByAppName(Map<AppNameAnnotation, Position> annotationsMap) {
		for (Map.Entry<AppNameAnnotation, Position> entry : annotationsMap.entrySet()) {
			if (entry.getKey().getText().equals(fAppName)) {
				entry.getKey().markSelected();
				return;
			}
		}
	}

	/**
	 * Returns the first 'white' char after a word appearing before the passed index
	 *
	 * @param text Text
	 * @param index Index to start looking from
	 * @return The first 'white' char position in a string
	 */
	private static int getLastWhiteCharIndex(String text, int index) {
		if (index == text.length()) {
			return index;
		}
		int i = index;
		for (; i >=  0 && Character.isWhitespace(text.charAt(i)); i--) {
			// Nothing to do
		}
		// Special case: if non white char is at position 'index' then return value of 'index'
		return i == index ? i : i + 1;
	}

}
