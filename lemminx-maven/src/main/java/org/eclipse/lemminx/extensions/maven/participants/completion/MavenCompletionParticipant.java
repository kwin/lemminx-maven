/*******************************************************************************
 * Copyright (c) 2019-2021 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lemminx.extensions.maven.participants.completion;

import static org.eclipse.lemminx.extensions.maven.DOMConstants.ARTIFACT_ID_ELT;
import static org.eclipse.lemminx.extensions.maven.DOMConstants.CONFIGURATION_ELT;
import static org.eclipse.lemminx.extensions.maven.DOMConstants.DEPENDENCIES_ELT;
import static org.eclipse.lemminx.extensions.maven.DOMConstants.DEPENDENCY_ELT;
import static org.eclipse.lemminx.extensions.maven.DOMConstants.GOAL_ELT;
import static org.eclipse.lemminx.extensions.maven.DOMConstants.GROUP_ID_ELT;
import static org.eclipse.lemminx.extensions.maven.DOMConstants.MODULE_ELT;
import static org.eclipse.lemminx.extensions.maven.DOMConstants.PARENT_ELT;
import static org.eclipse.lemminx.extensions.maven.DOMConstants.PHASE_ELT;
import static org.eclipse.lemminx.extensions.maven.DOMConstants.PLUGINS_ELT;
import static org.eclipse.lemminx.extensions.maven.DOMConstants.PLUGIN_ELT;
import static org.eclipse.lemminx.extensions.maven.DOMConstants.RELATIVE_PATH_ELT;
import static org.eclipse.lemminx.extensions.maven.DOMConstants.SCOPE_ELT;
import static org.eclipse.lemminx.extensions.maven.DOMConstants.VERSION_ELT;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.maven.Maven;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.index.ArtifactInfo;
import org.apache.maven.index.artifact.Gav;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.plugin.InvalidPluginDescriptorException;
import org.apache.maven.plugin.PluginDescriptorParsingException;
import org.apache.maven.plugin.PluginResolutionException;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.project.MavenProject;
import org.eclipse.lemminx.commons.BadLocationException;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMElement;
import org.eclipse.lemminx.dom.DOMNode;
import org.eclipse.lemminx.extensions.maven.DependencyScope;
import org.eclipse.lemminx.extensions.maven.MavenLemminxExtension;
import org.eclipse.lemminx.extensions.maven.MojoParameter;
import org.eclipse.lemminx.extensions.maven.Phase;
import org.eclipse.lemminx.extensions.maven.participants.hover.MavenHoverParticipant;
import org.eclipse.lemminx.extensions.maven.searcher.RemoteRepositoryIndexSearcher;
import org.eclipse.lemminx.extensions.maven.utils.DOMUtils;
import org.eclipse.lemminx.extensions.maven.utils.MavenParseUtils;
import org.eclipse.lemminx.extensions.maven.utils.MavenPluginUtils;
import org.eclipse.lemminx.extensions.maven.utils.PlexusConfigHelper;
import org.eclipse.lemminx.services.extensions.CompletionParticipantAdapter;
import org.eclipse.lemminx.services.extensions.ICompletionRequest;
import org.eclipse.lemminx.services.extensions.ICompletionResponse;
import org.eclipse.lemminx.utils.XMLPositionUtility;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

public class MavenCompletionParticipant extends CompletionParticipantAdapter {

	private static final Logger LOGGER = Logger.getLogger(MavenCompletionParticipant.class.getName());
	private static final Pattern ARTIFACT_ID_PATTERN = Pattern.compile("[-.a-zA-Z0-9]+");

	static interface GAVInsertionStrategy {
		/**
		 * set current element value and add siblings as addition textEdits
		 */
		public static final GAVInsertionStrategy ELEMENT_VALUE_AND_SIBLING = new GAVInsertionStrategy() {
		};

		/**
		 * insert elements as children of the parent element
		 */
		public static final GAVInsertionStrategy CHILDREN_ELEMENTS = new GAVInsertionStrategy() {
		};

		public static final class NodeWithChildrenInsertionStrategy implements GAVInsertionStrategy {
			public final String elementName;

			public NodeWithChildrenInsertionStrategy(String elementName) {
				this.elementName = elementName;
			}
		}
	}

	private final MavenLemminxExtension plugin;

	public MavenCompletionParticipant(MavenLemminxExtension plugin) {
		this.plugin = plugin;
	}

	@Override
	public void onTagOpen(ICompletionRequest request, ICompletionResponse response, CancelChecker cancelChecker) throws Exception {
		if (!MavenLemminxExtension.match(request.getXMLDocument())) {
			return;
		}

		DOMNode tag = request.getNode();
		if (DOMUtils.isADescendantOf(tag, CONFIGURATION_ELT)) {
			collectPluginConfiguration(request).forEach(response::addCompletionItem);
			return;
		}
	}

	// TODO: Factor this with MavenHoverParticipant's equivalent method
	private List<CompletionItem> collectPluginConfiguration(ICompletionRequest request) {
		boolean supportsMarkdown = request.canSupportMarkupKind(MarkupKind.MARKDOWN);
		Set<MojoParameter> parameters;
		try {
			parameters = MavenPluginUtils.collectPluginConfigurationMojoParameters(request, plugin);
		} catch (PluginResolutionException | PluginDescriptorParsingException | InvalidPluginDescriptorException e) {
			LOGGER.log(Level.SEVERE, e.getMessage(), e);
			return null;
		}

		if (CONFIGURATION_ELT.equals(request.getParentElement().getLocalName())) {
			// The configuration element being completed is at the top level
			return parameters.stream()
					.map(param -> toTag(param.getName(),
							MavenPluginUtils.getMarkupDescription(param, null, supportsMarkdown), request))
					.collect(Collectors.toList());
		}

		// Nested case: node is a grand child of configuration
		// Get the node's ancestor which is a child of configuration
		DOMNode parentParameterNode = DOMUtils.findAncestorThatIsAChildOf(request, CONFIGURATION_ELT);
		if (parentParameterNode != null) {
			List<MojoParameter> parentParameters = parameters.stream()
					.filter(mojoParameter -> mojoParameter.getName().equals(parentParameterNode.getLocalName()))
					.collect(Collectors.toList());
			if (!parentParameters.isEmpty()) {
				MojoParameter parentParameter = parentParameters.get(0);

				if (parentParameter.getNestedParameters().size() == 1) {
					// The parent parameter must be a collection of a type
					MojoParameter nestedParameter = parentParameter.getNestedParameters().get(0);
					Class<?> potentialInlineType = PlexusConfigHelper.getRawType(nestedParameter.getParamType());
					if (potentialInlineType != null && PlexusConfigHelper.isInline(potentialInlineType)) {
						return Collections.singletonList(toTag(nestedParameter.getName(), MavenPluginUtils
								.getMarkupDescription(nestedParameter, parentParameter, supportsMarkdown), request));
					}
				}

				// Get all deeply nested parameters
				List<MojoParameter> nestedParameters = parentParameter.getFlattenedNestedParameters();
				nestedParameters.add(parentParameter);
				return nestedParameters.stream()
						.map(param -> toTag(param.getName(),
								MavenPluginUtils.getMarkupDescription(param, parentParameter, supportsMarkdown),
								request))
						.collect(Collectors.toList());
			}
		}
		return null;
	}

	@Override
	public void onXMLContent(ICompletionRequest request, ICompletionResponse response, CancelChecker cancelChecker) throws Exception {
		if (!MavenLemminxExtension.match(request.getXMLDocument())) {
			return;
		}

		if (request.getXMLDocument().getText().length() < 2) {
			response.addCompletionItem(createMinimalPOMCompletionSnippet(request));
		}
		DOMElement parent = request.getParentElement();
		if (parent == null || parent.getLocalName() == null) {
			return;
		}
		DOMElement grandParent = parent.getParentElement();
		boolean isPlugin = PLUGIN_ELT.equals(parent.getLocalName())
				|| (grandParent != null && PLUGIN_ELT.equals(grandParent.getLocalName()));
		boolean isParentDeclaration = PARENT_ELT.equals(parent.getLocalName())
				|| (grandParent != null && PARENT_ELT.equals(grandParent.getLocalName()));
		Optional<String> groupId = grandParent == null ? Optional.empty()
				: grandParent.getChildren().stream().filter(DOMNode::isElement)
						.filter(node -> GROUP_ID_ELT.equals(node.getLocalName()))
						.flatMap(node -> node.getChildren().stream()).map(DOMNode::getTextContent)
						.filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty()).findFirst();
		Optional<String> artifactId = grandParent == null ? Optional.empty()
				: grandParent.getChildren().stream().filter(DOMNode::isElement)
						.filter(node -> ARTIFACT_ID_ELT.equals(node.getLocalName()))
						.flatMap(node -> node.getChildren().stream()).map(DOMNode::getTextContent)
						.filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty()).findFirst();
		GAVInsertionStrategy gavInsertionStrategy = computeGAVInsertionStrategy(request);
		List<ArtifactInfo> allArtifactInfos = Collections.synchronizedList(new ArrayList<>());
		switch (parent.getLocalName()) {
		case SCOPE_ELT:
			collectSimpleCompletionItems(Arrays.asList(DependencyScope.values()), DependencyScope::getName,
					DependencyScope::getDescription, request).forEach(response::addCompletionAttribute);
			break;
		case PHASE_ELT:
			collectSimpleCompletionItems(Arrays.asList(Phase.ALL_STANDARD_PHASES), phase -> phase.id,
					phase -> phase.description, request).forEach(response::addCompletionAttribute);
			break;
		case GROUP_ID_ELT:
			if (isParentDeclaration) {
				Optional<MavenProject> filesystem = computeFilesystemParent(request);
				if (filesystem.isPresent()) {
					filesystem.map(this::toArtifactInfo).ifPresent(allArtifactInfos::add);
				}
				// TODO localRepo
				// TODO remoteRepos
			} else {
				// TODO if artifactId is set and match existing content, suggest only matching
				// groupId
				collectSimpleCompletionItems(
						isPlugin ? plugin.getLocalRepositorySearcher().searchPluginGroupIds()
								: plugin.getLocalRepositorySearcher().searchGroupIds(),
						Function.identity(), Function.identity(), request).forEach(response::addCompletionAttribute);
				internalCollectRemoteGAVCompletion(request, isPlugin, allArtifactInfos, response, cancelChecker);
			}
			internalCollectWorkspaceArtifacts(request, allArtifactInfos, groupId, artifactId);
			break;
		case ARTIFACT_ID_ELT:
			if (isParentDeclaration) {
				Optional<MavenProject> filesystem = computeFilesystemParent(request);
				if (filesystem.isPresent()) {
					filesystem.map(this::toArtifactInfo).ifPresent(allArtifactInfos::add);
				}
				// TODO localRepo
				// TODO remoteRepos
			} else {
				allArtifactInfos.addAll((isPlugin ? plugin.getLocalRepositorySearcher().getLocalPluginArtifacts()
						: plugin.getLocalRepositorySearcher().getLocalArtifactsLastVersion()).stream()
								.filter(gav -> !groupId.isPresent() || gav.getGroupId().equals(groupId.get()))
								// TODO pass description as documentation
								.map(this::toArtifactInfo).collect(Collectors.toList()));
				internalCollectRemoteGAVCompletion(request, isPlugin, allArtifactInfos, response, cancelChecker);
			}
			internalCollectWorkspaceArtifacts(request, allArtifactInfos, groupId, artifactId);
			break;
		case VERSION_ELT:
			if (!isParentDeclaration) {
				if (artifactId.isPresent()) {
					plugin.getLocalRepositorySearcher().getLocalArtifactsLastVersion().stream()
							.filter(gav -> gav.getArtifactId().equals(artifactId.get()))
							.filter(gav -> !groupId.isPresent() || gav.getGroupId().equals(groupId.get())).findAny()
							.map(Gav::getVersion).map(DefaultArtifactVersion::new)
							.map(version -> toCompletionItem(version.toString(), null, request.getReplaceRange()))
							.ifPresent(response::addCompletionItem);
					internalCollectRemoteGAVCompletion(request, isPlugin, allArtifactInfos, response, cancelChecker);
				}
			} else {
				Optional<MavenProject> filesystem = computeFilesystemParent(request);
				if (filesystem.isPresent()) {
					filesystem.map(this::toArtifactInfo).ifPresent(allArtifactInfos::add);
				}
				// TODO localRepo
				// TODO remoteRepos
			}
			internalCollectWorkspaceArtifacts(request, allArtifactInfos, groupId, artifactId);
			if (allArtifactInfos.isEmpty()) {
				response.addCompletionItem(toTextCompletionItem(request, "-SNAPSHOT"));
			}
			break;
		case MODULE_ELT:
			collectSubModuleCompletion(request).forEach(response::addCompletionItem);
			break;
		case RELATIVE_PATH_ELT:
			collectRelativePathCompletion(request).forEach(response::addCompletionItem);
			break;
		case DEPENDENCIES_ELT:
		case DEPENDENCY_ELT:
			// TODO completion/resolve to get description for local artifacts
			allArtifactInfos.addAll(plugin.getLocalRepositorySearcher().getLocalArtifactsLastVersion().stream()
					.map(this::toArtifactInfo).collect(Collectors.toList()));
			internalCollectRemoteGAVCompletion(request, false, allArtifactInfos, response, cancelChecker);
			break;
		case PLUGINS_ELT:
		case PLUGIN_ELT:
			// TODO completion/resolve to get description for local artifacts
			allArtifactInfos.addAll(plugin.getLocalRepositorySearcher().getLocalPluginArtifacts().stream()
					.map(this::toArtifactInfo).collect(Collectors.toList()));
			internalCollectRemoteGAVCompletion(request, true, allArtifactInfos, response, cancelChecker);
			break;
		case PARENT_ELT:
			Optional<MavenProject> filesystem = computeFilesystemParent(request);
			if (filesystem.isPresent()) {
				filesystem.map(this::toArtifactInfo).ifPresent(allArtifactInfos::add);
			} else {
				// TODO localRepo
				// TODO remoteRepos
			}
			break;
		case GOAL_ELT:
			collectGoals(request).forEach(response::addCompletionItem);
			break;
		}
		if (!allArtifactInfos.isEmpty()) {
			Comparator<ArtifactInfo> artifactInfoComparator = Comparator
					.comparing(artifact -> new DefaultArtifactVersion(artifact.getVersion()));
			final Comparator<ArtifactInfo> highestVersionWithDescriptionComparator = artifactInfoComparator
					.thenComparing(
							artifactInfo -> artifactInfo.getDescription() != null ? artifactInfo.getDescription() : "");
			allArtifactInfos.stream()
					.collect(Collectors.groupingBy(artifact -> artifact.getGroupId() + ":" + artifact.getArtifactId()))
					.values().stream()
					.map(artifacts -> Collections.max(artifacts, highestVersionWithDescriptionComparator))
					.map(artifactInfo -> toGAVCompletionItem(artifactInfo, request, gavInsertionStrategy))
					.forEach(response::addCompletionItem);
		}
		if (request.getNode().isText()) {
			completeProperties(request).forEach(response::addCompletionAttribute);
		}
	}

	private CompletionItem toTextCompletionItem(ICompletionRequest request, String text) throws BadLocationException {
		CompletionItem res = new CompletionItem(text);
		res.setFilterText(text);
		TextEdit edit = new TextEdit();
		edit.setNewText(text);
		res.setTextEdit(Either.forLeft(edit));
		Position endOffset = request.getXMLDocument().positionAt(request.getOffset());
		for (int startOffset = Math.max(0, request.getOffset() - text.length()); startOffset <= request
				.getOffset(); startOffset++) {
			String prefix = request.getXMLDocument().getText().substring(startOffset, request.getOffset());
			if (text.startsWith(prefix)) {
				edit.setRange(new Range(request.getXMLDocument().positionAt(startOffset), endOffset));
				return res;
			}
		}
		edit.setRange(new Range(endOffset, endOffset));
		return res;
	}

	private ArtifactInfo toArtifactInfo(Gav gav) {
		return new ArtifactInfo(null, gav.getGroupId(), gav.getArtifactId(), gav.getVersion(), null, null);
	}

	private ArtifactInfo toArtifactInfo(MavenProject project) {
		ArtifactInfo artifactInfo = new ArtifactInfo(null, project.getGroupId(), project.getArtifactId(),
				project.getVersion(), null, null);
		artifactInfo.setDescription(project.getDescription());
		return artifactInfo;
	}

	private GAVInsertionStrategy computeGAVInsertionStrategy(ICompletionRequest request) {
		if (request.getParentElement() == null) {
			return null;
		}
		switch (request.getParentElement().getLocalName()) {
		case DEPENDENCIES_ELT:
			return new GAVInsertionStrategy.NodeWithChildrenInsertionStrategy(DEPENDENCY_ELT);
		case DEPENDENCY_ELT:
			return GAVInsertionStrategy.CHILDREN_ELEMENTS;
		case PLUGINS_ELT:
			return new GAVInsertionStrategy.NodeWithChildrenInsertionStrategy(PLUGIN_ELT);
		case PLUGIN_ELT:
			return GAVInsertionStrategy.CHILDREN_ELEMENTS;
		case ARTIFACT_ID_ELT:
			return GAVInsertionStrategy.ELEMENT_VALUE_AND_SIBLING;
		case PARENT_ELT:
			return GAVInsertionStrategy.CHILDREN_ELEMENTS;
		}
		return GAVInsertionStrategy.ELEMENT_VALUE_AND_SIBLING;
	}

	private Optional<MavenProject> computeFilesystemParent(ICompletionRequest request) {
		Optional<String> relativePath = null;
		if (request.getParentElement().getLocalName().equals(PARENT_ELT)) {
			relativePath = DOMUtils.findChildElementText(request.getNode(), RELATIVE_PATH_ELT);
		} else {
			relativePath = DOMUtils.findChildElementText(request.getParentElement().getParentElement(),
					RELATIVE_PATH_ELT);
		}
		if (!relativePath.isPresent()) {
			relativePath = Optional.of("..");
		}
		File referencedTargetPomFile = new File(
				new File(URI.create(request.getXMLDocument().getTextDocument().getUri())).getParentFile(),
				relativePath.orElse(""));
		if (referencedTargetPomFile.isDirectory()) {
			referencedTargetPomFile = new File(referencedTargetPomFile, Maven.POMv4);
		}
		if (referencedTargetPomFile.isFile()) {
			return plugin.getProjectCache().getSnapshotProject(referencedTargetPomFile);
		}
		return Optional.empty();
	}

	private CompletionItem createMinimalPOMCompletionSnippet(ICompletionRequest request)
			throws IOException, BadLocationException {
		CompletionItem item = new CompletionItem("minimal pom content");
		item.setKind(CompletionItemKind.Snippet);
		item.setInsertTextFormat(InsertTextFormat.Snippet);
		Model model = new Model();
		model.setModelVersion("4.0.0");
		model.setArtifactId(
				new File(URI.create(request.getXMLDocument().getTextDocument().getUri())).getParentFile().getName());
		MavenXpp3Writer writer = new MavenXpp3Writer();
		try (ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
			writer.write(stream, model);
			TextEdit textEdit = new TextEdit(
					new Range(new Position(0, 0),
							request.getXMLDocument().positionAt(request.getXMLDocument().getText().length())),
					new String(stream.toByteArray()));
			item.setTextEdit(Either.forLeft(textEdit));
		}
		return item;
	}

	private Collection<CompletionItem> collectGoals(ICompletionRequest request) {
		PluginDescriptor pluginDescriptor;
		try {
			pluginDescriptor = MavenPluginUtils.getContainingPluginDescriptor(request, plugin);
			return collectSimpleCompletionItems(pluginDescriptor.getMojos(), MojoDescriptor::getGoal,
					MojoDescriptor::getDescription, request);
		} catch (PluginResolutionException | PluginDescriptorParsingException | InvalidPluginDescriptorException e) {
			LOGGER.log(Level.SEVERE, e.getMessage(), e);
		}
		return Collections.emptySet();
	}

	private CompletionItem toGAVCompletionItem(ArtifactInfo artifactInfo, ICompletionRequest request,
			GAVInsertionStrategy strategy) {
		boolean insertGroupId = strategy instanceof GAVInsertionStrategy.NodeWithChildrenInsertionStrategy || !DOMUtils
				.findChildElementText(request.getParentElement().getParentElement(), GROUP_ID_ELT).isPresent();
		boolean insertVersion = strategy instanceof GAVInsertionStrategy.NodeWithChildrenInsertionStrategy || !DOMUtils
				.findChildElementText(request.getParentElement().getParentElement(), VERSION_ELT).isPresent();
		CompletionItem item = new CompletionItem();
		if (artifactInfo.getDescription() != null) {
			item.setDocumentation(artifactInfo.getDescription());
		}
		DOMDocument doc = request.getXMLDocument();
		Range replaceRange = doc.getTextDocument().getWordRangeAt(request.getOffset(), ARTIFACT_ID_PATTERN);

		TextEdit textEdit = new TextEdit();
		item.setTextEdit(Either.forLeft(textEdit));
		textEdit.setRange(replaceRange);
		if (strategy == GAVInsertionStrategy.ELEMENT_VALUE_AND_SIBLING) {
			item.setKind(CompletionItemKind.Value);
			textEdit.setRange(replaceRange);
			switch (request.getParentElement().getLocalName()) {
			case ARTIFACT_ID_ELT:
				item.setLabel(artifactInfo.getArtifactId()
						+ (insertGroupId || insertVersion
								? " - " + artifactInfo.getGroupId() + ":" + artifactInfo.getArtifactId() + ":"
										+ artifactInfo.getVersion()
								: ""));
				textEdit.setNewText(artifactInfo.getArtifactId());
				item.setFilterText(artifactInfo.getArtifactId());
				List<TextEdit> additionalEdits = new ArrayList<>(2);
				if (insertGroupId) {
					Position insertionPosition;
					try {
						insertionPosition = request.getXMLDocument()
								.positionAt(request.getParentElement().getParentElement().getStartTagCloseOffset() + 1);
						additionalEdits.add(new TextEdit(new Range(insertionPosition, insertionPosition),
								request.getLineIndentInfo().getLineDelimiter()
										+ request.getLineIndentInfo().getWhitespacesIndent() + "<groupId>"
										+ artifactInfo.getGroupId() + "</groupId>"));
					} catch (BadLocationException e) {
						// TODO Auto-generated catch block
						LOGGER.log(Level.SEVERE, e.getMessage(), e);
					}
				}
				if (insertVersion) {
					Position insertionPosition;
					try {
						insertionPosition = request.getXMLDocument()
								.positionAt(request.getParentElement().getEndTagCloseOffset() + 1);
						additionalEdits.add(new TextEdit(new Range(insertionPosition, insertionPosition),
								request.getLineIndentInfo().getLineDelimiter()
										+ request.getLineIndentInfo().getWhitespacesIndent()
										+ DOMUtils.getOneLevelIndent(request) + "<version>" + artifactInfo.getVersion()
										+ "</version>"));
					} catch (BadLocationException e) {
						// TODO Auto-generated catch block
						LOGGER.log(Level.SEVERE, e.getMessage(), e);
					}
				}
				if (!additionalEdits.isEmpty()) {
					item.setAdditionalTextEdits(additionalEdits);
				}
				return item;
			case GROUP_ID_ELT:
				item.setLabel(artifactInfo.getGroupId());
				textEdit.setNewText(artifactInfo.getGroupId());
				return item;
			case VERSION_ELT:
				item.setLabel(artifactInfo.getVersion());
				textEdit.setNewText(artifactInfo.getVersion());
				return item;
			}
		} else {
			item.setLabel(artifactInfo.getArtifactId() + " - " + artifactInfo.getGroupId() + ":"
					+ artifactInfo.getArtifactId() + ":" + artifactInfo.getVersion());
			item.setKind(CompletionItemKind.Struct);
			item.setFilterText(artifactInfo.getArtifactId());
			try {
				textEdit.setRange(replaceRange);
				String newText = "";
				String suffix = "";
				String gavElementsIndent = request.getLineIndentInfo().getWhitespacesIndent();
				if (strategy instanceof GAVInsertionStrategy.NodeWithChildrenInsertionStrategy) {
					String elementName = ((GAVInsertionStrategy.NodeWithChildrenInsertionStrategy) strategy).elementName;
					gavElementsIndent += DOMUtils.getOneLevelIndent(request);
					newText += "<" + elementName + ">" + request.getLineIndentInfo().getLineDelimiter()
							+ gavElementsIndent;
					suffix = request.getLineIndentInfo().getLineDelimiter()
							+ request.getLineIndentInfo().getWhitespacesIndent() + "</" + elementName + ">";
				}
				if (insertGroupId) {
					newText += "<groupId>" + artifactInfo.getGroupId() + "</groupId>"
							+ request.getLineIndentInfo().getLineDelimiter() + gavElementsIndent;
				}
				newText += "<artifactId>" + artifactInfo.getArtifactId() + "</artifactId>";
				if (insertVersion) {
					newText += request.getLineIndentInfo().getLineDelimiter() + gavElementsIndent;
					newText += "<version>" + artifactInfo.getVersion() + "</version>";
				}
				newText += suffix;
				textEdit.setNewText(newText);
			} catch (BadLocationException ex) {
				LOGGER.log(Level.SEVERE, ex.getCause().toString(), ex);
				return null;
			}
		}
		return item;
	}

	private CompletionItem toTag(String name, MarkupContent description, ICompletionRequest request) {
		CompletionItem res = new CompletionItem(name);
		res.setDocumentation(Either.forRight(description));
		res.setInsertTextFormat(InsertTextFormat.Snippet);
		TextEdit edit = new TextEdit();
		edit.setNewText('<' + name + ">$0</" + name + '>');
		edit.setRange(request.getReplaceRange());
		res.setTextEdit(Either.forLeft(edit));
		res.setKind(CompletionItemKind.Field);
		res.setFilterText(edit.getNewText());
		res.setSortText(name);
		return res;
	}

	private Collection<CompletionItem> completeProperties(ICompletionRequest request) {
		MavenProject project = plugin.getProjectCache().getLastSuccessfulMavenProject(request.getXMLDocument());
		if (project == null) {
			return Collections.emptySet();
		}
		Map<String, String> allProps = MavenHoverParticipant.getMavenProjectProperties(project);

		return allProps.entrySet().stream().map(property -> {
			try {
				CompletionItem item = toTextCompletionItem(request, "${" + property.getKey() + '}');
				item.setDocumentation(
						"Default Value: " + (property.getValue() != null ? property.getValue() : "unknown"));
				item.setKind(CompletionItemKind.Property);
				// '$' sorts before alphabet characters, so we add z to make it appear later in
				// proposals
				item.setSortText("z${" + property.getKey() + "}");
				if (property.getKey().contains("env.")) {
					// We don't want environment variables at the top of the completion proposals
					item.setSortText("z${zzz" + property.getKey() + "}");
				}
				return item;
			} catch (BadLocationException e) {
				LOGGER.log(Level.SEVERE, e.getMessage(), e);
				return toErrorCompletionItem(e);
			}
		}).collect(Collectors.toList());
	}

	private CompletionItem toErrorCompletionItem(Throwable e) {
		CompletionItem res = new CompletionItem("Error: " + e.getMessage());
		res.setDocumentation(ExceptionUtils.getStackTrace(e));
		res.setInsertText("");
		return res;
	}

	private void internalCollectRemoteGAVCompletion(ICompletionRequest request, boolean onlyPlugins,
			Collection<ArtifactInfo> artifactInfosCollector, ICompletionResponse nonArtifactCollector, CancelChecker cancelChecker) {
		DOMElement node = request.getParentElement();
		Dependency artifactToSearch = MavenParseUtils.parseArtifact(node);
		if (artifactToSearch == null) {
			return;
		}
		DOMDocument doc = request.getXMLDocument();

		Range range = XMLPositionUtility.createRange(node.getStartTagCloseOffset() + 1, node.getEndTagOpenOffset(),
				doc);
		MavenProject project = plugin.getProjectCache().getLastSuccessfulMavenProject(doc);
		List<String> remoteArtifactRepositories = project != null ? //
				project.getRemoteArtifactRepositories().stream().map(ArtifactRepository::getUrl)
						.collect(Collectors.toList())
				: //
				Collections.singletonList(RemoteRepositoryIndexSearcher.CENTRAL_REPO.getUrl());
		Set<CompletionItem> updateItems = Collections.synchronizedSet(new HashSet<>(remoteArtifactRepositories.size()));
		plugin.getIndexSearcher().ifPresent(indexSearcher -> {
			try {
				CompletableFuture.allOf(remoteArtifactRepositories.stream().map(repository -> {
					cancelChecker.checkCanceled();
					final CompletionItem updatingItem = new CompletionItem("Updating index for " + repository);
					updatingItem.setPreselect(true);
					updatingItem.setInsertText("");
					updatingItem.setKind(CompletionItemKind.Event);
					updateItems.add(updatingItem);
					return indexSearcher.getIndexingContext(URI.create(repository)).thenApplyAsync(index -> {
						cancelChecker.checkCanceled();
						switch (node.getLocalName()) {
						case GROUP_ID_ELT:
							// TODO: just pass only plugins boolean, and make getGroupId's accept a boolean
							// parameter
							if (onlyPlugins) {
								indexSearcher.getPluginGroupIds(artifactToSearch, index).stream()
										.map(groupId -> toCompletionItem(groupId, null, range))
										.forEach(nonArtifactCollector::addCompletionItem);
							} else {
								indexSearcher.getGroupIds(artifactToSearch, index).stream()
										.map(groupId -> toCompletionItem(groupId, null, range))
										.forEach(nonArtifactCollector::addCompletionItem);
							}
							cancelChecker.checkCanceled();
							return updatingItem;
						case ARTIFACT_ID_ELT:
							if (onlyPlugins) {
								artifactInfosCollector
										.addAll(indexSearcher.getPluginArtifacts(artifactToSearch, index));
							} else {
								artifactInfosCollector.addAll(indexSearcher.getArtifacts(artifactToSearch, index));
							}
							cancelChecker.checkCanceled();
							return updatingItem;
						case VERSION_ELT:
							if (onlyPlugins) {
								indexSearcher.getPluginArtifactVersions(artifactToSearch, index).stream()
										.map(version -> toCompletionItem(version.toString(), null, range))
										.forEach(nonArtifactCollector::addCompletionItem);
							} else {
								indexSearcher.getArtifactVersions(artifactToSearch, index).stream()
										.map(version -> toCompletionItem(version.toString(), null, range))
										.forEach(nonArtifactCollector::addCompletionItem);
							}
							cancelChecker.checkCanceled();
							return updatingItem;
						case DEPENDENCIES_ELT:
						case DEPENDENCY_ELT:
							artifactInfosCollector.addAll(indexSearcher.getArtifacts(artifactToSearch, index));
							cancelChecker.checkCanceled();
							return updatingItem;
						case PLUGINS_ELT:
						case PLUGIN_ELT:
							artifactInfosCollector.addAll(indexSearcher.getPluginArtifacts(artifactToSearch, index));
							cancelChecker.checkCanceled();
							return updatingItem;
						}
						return (CompletionItem) null;
					}).whenComplete((ok, error) -> updateItems.remove(ok));
				}).toArray(CompletableFuture<?>[]::new)).get(2, TimeUnit.SECONDS);
			} catch (InterruptedException | ExecutionException exception) {
				LOGGER.log(Level.SEVERE, exception.getCause().toString(), exception);
			} catch (TimeoutException e) {
				// nothing to log, some work still pending
				updateItems.forEach(nonArtifactCollector::addCompletionItem);
			}
		});
	}

	private Collection<CompletionItem> collectSubModuleCompletion(ICompletionRequest request) {
		DOMDocument doc = request.getXMLDocument();
		File docFolder = new File(URI.create(doc.getTextDocument().getUri())).getParentFile();
		String prefix = request.getNode().getNodeValue() != null ? request.getNode().getNodeValue() : "";
		File prefixFile = new File(docFolder, prefix);
		List<File> files = new ArrayList<>();
		if (!prefix.isEmpty() && !prefix.endsWith("/")) {
			files.addAll(Arrays.asList(
					prefixFile.getParentFile().listFiles((dir, name) -> name.startsWith(prefixFile.getName()))));
		}
		if (prefixFile.isDirectory()) {
			files.addAll(Arrays.asList(prefixFile.listFiles(File::isDirectory)));
		}
		// make folder that have a pom show higher
		files.sort(Comparator.comparing((File file) -> new File(file, Maven.POMv4).exists()).reversed()
				.thenComparing(Function.identity()));
		if (prefix.isEmpty()) {
			files.add(docFolder.getParentFile());
		}
		return files.stream().map(file -> toFileCompletionItem(file, docFolder, request)).collect(Collectors.toList());
	}

	private Collection<CompletionItem> collectRelativePathCompletion(ICompletionRequest request) {
		DOMDocument doc = request.getXMLDocument();
		File docFile = new File(URI.create(doc.getTextDocument().getUri()));
		File docFolder = docFile.getParentFile();
		String prefix = request.getNode().getNodeValue() != null ? request.getNode().getNodeValue() : "";
		File prefixFile = new File(docFolder, prefix);
		List<File> files = new ArrayList<>();
		if (prefix.isEmpty()) {
			Arrays.stream(docFolder.getParentFile().listFiles()).filter(file -> file.getName().contains(PARENT_ELT))
					.map(file -> new File(file, Maven.POMv4)).filter(File::isFile).forEach(files::add);
			files.add(docFolder.getParentFile());
		} else {
			try {
				prefixFile = prefixFile.getCanonicalFile();
			} catch (IOException e) {
				LOGGER.log(Level.SEVERE, e.getMessage(), e);
			}
			if (!prefix.endsWith("/")) {
				final File thePrefixFile = prefixFile;
				files.addAll(Arrays.asList(prefixFile.getParentFile()
						.listFiles(file -> file.getName().startsWith(thePrefixFile.getName()))));
			}
		}
		if (prefixFile.isDirectory()) {
			files.addAll(Arrays.asList(prefixFile.listFiles()));
		}
		return files.stream().filter(file -> file.getName().equals(Maven.POMv4) || file.isDirectory())
				.filter(file -> !(file.equals(docFolder) || file.equals(docFile))).flatMap(file -> {
					if (docFile.toPath().startsWith(file.toPath()) || file.getName().contains(PARENT_ELT)) {
						File pomFile = new File(file, Maven.POMv4);
						if (pomFile.exists()) {
							return Stream.of(pomFile, file);
						}
					}
					return Stream.of(file);
				}).sorted(Comparator.comparing(File::isFile) // pom files before folders
						.thenComparing(
								file -> (file.isFile() && docFile.toPath().startsWith(file.getParentFile().toPath()))
										|| (file.isDirectory() && file.equals(docFolder.getParentFile()))) // `../pom.xml`
																											// before
																											// ...
						.thenComparing(file -> file.getParentFile().getName().contains(PARENT_ELT)) // folders
																									// containing
																									// "parent"
																									// before...
						.thenComparing(file -> file.getParentFile().getParentFile().equals(docFolder.getParentFile())) // siblings
																														// before...
						.reversed().thenComparing(Function.identity())// other folders and files
				).map(file -> toFileCompletionItem(file, docFolder, request)).collect(Collectors.toList());
	}

	private CompletionItem toFileCompletionItem(File file, File referenceFolder, ICompletionRequest request) {
		CompletionItem res = new CompletionItem();
		Path path = referenceFolder.toPath().relativize(file.toPath());
		StringBuilder builder = new StringBuilder(path.toString().length());
		Path current = path;
		while (current != null) {
			if (!current.equals(path)) {
				// Only append "/" for parent directories
				builder.insert(0, '/');
			}
			builder.insert(0, current.getFileName());
			current = current.getParent();
		}

		Range replaceRange = request.getReplaceRange();

		/*
		 * Temporary workaround for https://github.com/eclipse/lemminx-maven/pull/127
		 * Workaround involves overriding the replace range of the entire node text
		 * rather than some portion after the file separator. TODO: Remove try catch
		 * block after upstream fix has been merged. Upstream fix:
		 * https://github.com/eclipse/lemminx/issues/723
		 */
		try {
			DOMElement parentElement = request.getParentElement();
			int startOffset = parentElement.getStartTagCloseOffset() + 1;
			Position start = parentElement.getOwnerDocument().positionAt(startOffset);
			Position end = request.getPosition();
			int endOffset = parentElement.getEndTagOpenOffset();
			if (endOffset > 0) {
				end = request.getXMLDocument().positionAt(endOffset);
			}
			replaceRange = new Range(start, end);
		} catch (BadLocationException e) {
			LOGGER.log(Level.SEVERE, e.getMessage(), e);
		}

		String pathString = builder.toString();
		res.setLabel(pathString);
		res.setFilterText(pathString);
		res.setKind(file.isDirectory() ? CompletionItemKind.Folder : CompletionItemKind.File);
		res.setTextEdit(Either.forLeft(new TextEdit(replaceRange, pathString)));

		return res;
	}

	private <T> Collection<CompletionItem> collectSimpleCompletionItems(Collection<T> items,
			Function<T, String> insertionTextExtractor, Function<T, String> documentationExtractor,
			ICompletionRequest request) {
		DOMElement node = request.getParentElement();
		DOMDocument doc = request.getXMLDocument();
		boolean needClosingTag = node.getEndTagOpenOffset() == DOMNode.NULL_VALUE;
		Range range = XMLPositionUtility.createRange(node.getStartTagCloseOffset() + 1,
				needClosingTag ? node.getStartTagOpenOffset() + 1 : node.getEndTagOpenOffset(), doc);

		return items.stream().map(o -> {
			String label = insertionTextExtractor.apply(o);
			CompletionItem item = new CompletionItem();
			item.setLabel(label);
			String insertText = label + (needClosingTag ? "</" + node.getTagName() + ">" : "");
			item.setKind(CompletionItemKind.Property);
			item.setDocumentation(Either.forLeft(documentationExtractor.apply(o)));
			item.setFilterText(insertText);
			item.setTextEdit(Either.forLeft(new TextEdit(range, insertText)));
			item.setInsertTextFormat(InsertTextFormat.PlainText);
			return item;
		}).collect(Collectors.toList());
	}

	/**
	 * Utility function, takes a label string, description and range and returns a
	 * CompletionItem
	 *
	 * @param description Completion description
	 * @param label       Completion label
	 * @return CompletionItem resulting from the label, description and range given
	 * @param range Range where the completion will be inserted
	 */
	private static CompletionItem toCompletionItem(String label, String description, Range range) {
		CompletionItem item = new CompletionItem();
		item.setLabel(label);
		item.setSortText(label);
		item.setKind(CompletionItemKind.Property);
		String insertText = label;
		if (description != null) {
			item.setDocumentation(Either.forLeft(description));
		}
		item.setFilterText(insertText);
		item.setInsertTextFormat(InsertTextFormat.PlainText);
		item.setTextEdit(Either.forLeft(new TextEdit(range, insertText)));
		return item;
	}

	/*
	 * Utility function which can be passed as an argument to filter() to filter out
	 * duplicate elements by a property.
	 */
	public static <T> Predicate<T> distinctByKey(Function<? super T, Object> keyExtractor) {
		Map<Object, Boolean> map = new ConcurrentHashMap<>();
		return t -> map.putIfAbsent(keyExtractor.apply(t), Boolean.TRUE) == null;
	}
	
	private void internalCollectWorkspaceArtifacts(ICompletionRequest request, Collection<ArtifactInfo> artifactInfosCollector,
			Optional<String> groupId, Optional<String> artifactId) {
		DOMElement parent = request.getParentElement();
		if (parent == null || parent.getLocalName() == null) {
			return;
		}
		DOMElement grandParent = parent.getParentElement();
		if (grandParent == null || 
				(!PARENT_ELT.equals(grandParent.getLocalName()) &&
						!DEPENDENCY_ELT.equals(grandParent.getLocalName()) &&
						!PLUGIN_ELT.equals(grandParent.getLocalName()))) {
			return;
		}
		
		String groupIdFilter = groupId.orElse(null);
		String artifactIdFilter = artifactId.orElse(null);
		
		switch (parent.getLocalName()) {
		case ARTIFACT_ID_ELT:
			plugin.getProjectCache().getProjects().stream().map(this::toArtifactInfo)
			.filter(a -> groupIdFilter == null || groupIdFilter.equals(a.getGroupId()))
			.forEach(artifactInfosCollector::add);
			break;
		case GROUP_ID_ELT:
		case VERSION_ELT:
			plugin.getProjectCache().getProjects().stream().map(this::toArtifactInfo)
			.filter(a -> artifactIdFilter == null || artifactIdFilter.equals(a.getArtifactId()))
			.forEach(artifactInfosCollector::add);
			break;
		}
	}
}
