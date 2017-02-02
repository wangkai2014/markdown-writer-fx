/*
 * Copyright (c) 2015 Karl Tauber <karl at jformdesigner dot com>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  o Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *
 *  o Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.markdownwriterfx.spellchecker;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.beans.InvalidationListener;
import javafx.beans.WeakInvalidationListener;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.text.TextFlow;
import org.apache.commons.lang3.StringUtils;
import org.fxmisc.richtext.StyleClassedTextArea;
import org.fxmisc.richtext.model.PlainTextChange;
import org.languagetool.JLanguageTool;
import org.languagetool.language.AmericanEnglish;
import org.languagetool.markup.AnnotatedText;
import org.languagetool.markup.AnnotatedTextBuilder;
import org.languagetool.rules.RuleMatch;
import org.markdownwriterfx.editor.MarkdownEditorPane;
import org.markdownwriterfx.editor.ParagraphOverlayGraphicFactory;
import org.markdownwriterfx.options.Options;
import org.reactfx.EventStream;
import org.reactfx.Subscription;
import org.reactfx.util.Try;
import com.vladsch.flexmark.ast.Block;
import com.vladsch.flexmark.ast.Code;
import com.vladsch.flexmark.ast.HardLineBreak;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.Node;
import com.vladsch.flexmark.ast.NodeVisitor;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ast.SoftLineBreak;
import com.vladsch.flexmark.ast.Text;

/**
 * Spell checker for an instance of StyleClassedTextArea
 *
 * @author Karl Tauber
 */
public class SpellChecker
{
	private final MarkdownEditorPane editor;
	private final StyleClassedTextArea textArea;
	private final ParagraphOverlayGraphicFactory overlayGraphicFactory;
	private final InvalidationListener optionsListener;
	private List<SpellBlockProblems> spellProblems;

	private Subscription textChangesSubscribtion;
	private SpellCheckerOverlayFactory spellCheckerOverlayFactory;

	// global executor used for all spell checking
	private static ExecutorService executor;

	// global JLanguageTool used in executor
	private static JLanguageTool languageTool;

	public SpellChecker(MarkdownEditorPane editor, StyleClassedTextArea textArea,
		ParagraphOverlayGraphicFactory overlayGraphicFactory)
	{
		this.editor = editor;
		this.textArea = textArea;
		this.overlayGraphicFactory = overlayGraphicFactory;

		enableDisable();

		// listen to option changes
		optionsListener = e -> {
			if (textArea.getScene() == null)
				return; // editor closed but not yet GCed

			if (e == Options.spellCheckerProperty())
				enableDisable();
		};
		WeakInvalidationListener weakOptionsListener = new WeakInvalidationListener(optionsListener);
		Options.spellCheckerProperty().addListener(weakOptionsListener);
	}

	private void enableDisable() {
		boolean spellChecker = Options.isSpellChecker();
		if (spellChecker && spellCheckerOverlayFactory == null) {
			if (executor == null) {
				executor = Executors.newSingleThreadExecutor(runnable -> {
					Thread thread = Executors.defaultThreadFactory().newThread(runnable);
					thread.setDaemon(true); // allow quitting app without shutting down executor
					return thread;
				});
			}

	        EventStream<PlainTextChange> textChanges = textArea.plainTextChanges();
			textChangesSubscribtion = textChanges
				.hook(this::updateSpellRangeOffsets)
				.successionEnds(Duration.ofMillis(500))
				.supplyTask(this::checkAsync)
				.awaitLatest(textChanges)
				.subscribe(this::checkFinished);

	        spellCheckerOverlayFactory = new SpellCheckerOverlayFactory(() -> spellProblems);
			overlayGraphicFactory.addOverlayFactory(spellCheckerOverlayFactory);

			//TODO check current text
		} else if (!spellChecker && spellCheckerOverlayFactory != null) {
			textChangesSubscribtion.unsubscribe();
			textChangesSubscribtion = null;

			overlayGraphicFactory.removeOverlayFactory(spellCheckerOverlayFactory);
			spellCheckerOverlayFactory = null;

			languageTool = null;

			if (executor != null) {
				executor.shutdown();
				executor = null;
			}
		}
	}

	private Task<List<SpellBlockProblems>> checkAsync() {
        Node astRoot = editor.getMarkdownAST();
        Task<List<SpellBlockProblems>> task = new Task<List<SpellBlockProblems>>() {
            @Override
            protected List<SpellBlockProblems> call() throws Exception {
                return check(astRoot);
            }
        };
        executor.execute(task);
        return task;
    }

	private void checkFinished(Try<List<SpellBlockProblems>> result) {
		if (overlayGraphicFactory == null)
			return; // ignore result; user turned spell checking off

		if (result.isSuccess()) {
			spellProblems = result.get();
			overlayGraphicFactory.update();
		} else {
			//TODO
			result.getFailure().printStackTrace();
		}
	}

	private List<SpellBlockProblems> check(Node astRoot) throws IOException {
		if (languageTool == null)
			languageTool = new JLanguageTool(new AmericanEnglish());
		languageTool.disableRule("WHITESPACE_RULE");

		// find nodes that should be checked
		ArrayList<Node> nodesToCheck = new ArrayList<>();
		NodeVisitor visitor = new NodeVisitor(Collections.emptyList()) {
			@Override
			public void visit(Node node) {
				if (node instanceof Paragraph || node instanceof Heading)
					nodesToCheck.add(node);

				if (node instanceof Block)
					visitChildren(node);
			}
		};
		visitor.visit(astRoot);

		// check spelling of nodes
		ArrayList<SpellBlockProblems> spellProblems = new ArrayList<>();
		for (Node node : nodesToCheck) {
			AnnotatedText annotatedText = annotatedNodeText(node);
			List<RuleMatch> ruleMatches = languageTool.check(annotatedText);

			spellProblems.add(new SpellBlockProblems(node.getStartOffset(), node.getEndOffset(), ruleMatches));
		}

		return spellProblems;
	}

	private AnnotatedText annotatedNodeText(Node node) {
		AnnotatedTextBuilder builder = new AnnotatedTextBuilder();
		NodeVisitor visitor = new NodeVisitor(Collections.emptyList()) {
			int prevTextEnd = node.getStartOffset();

			@Override
			public void visit(Node node) {
				if (node instanceof Text)
					addText(node.getStartOffset(), node.getChars().toString());
				else if (node instanceof Code)
					addText(((Code)node).getText().getStartOffset(), ((Code)node).getText().toString());
				else if (node instanceof SoftLineBreak)
					addText(node.getStartOffset(), " ");
				else if (node instanceof HardLineBreak)
					addText(node.getStartOffset(), "\n");
				else
					visitChildren(node);
			}

			private void addText(int start, String text) {
				if (start > prevTextEnd)
					builder.addMarkup(getMarkupFiller(start - prevTextEnd));
				builder.addText(text);
				prevTextEnd = start + text.length();
			}
		};
		visitor.visit(node);
		return builder.build();
	}

	private static final ArrayList<String> markupFiller = new ArrayList<>();
	private String getMarkupFiller(int length) {
		if (markupFiller.isEmpty()) {
			for (int i = 1; i <= 16; i++)
				markupFiller.add(StringUtils.repeat('#', i));
		}

		if (length <= markupFiller.size())
			return markupFiller.get(length - 1);
		return StringUtils.repeat('#', length);
	}

	private void updateSpellRangeOffsets(PlainTextChange e) {
		if (spellProblems == null)
			return;

		int position = e.getPosition();
		int inserted = e.getInserted().length();
		int removed = e.getRemoved().length();

		for (SpellBlockProblems blockProblems : spellProblems) {
			blockProblems.updateOffsets(position, inserted, removed);
			for (SpellProblem problem : blockProblems.problems)
				problem.updateOffsets(position, inserted, removed);
		}
	}

	//---- context menu -------------------------------------------------------

	private static final String CONTEXT_SPELL_PROBLEM_ITEM = "spell-problem-item";
	private static final Pattern SUGGESTION_PATTERN = Pattern.compile("<suggestion>(.*?)</suggestion>");

	public void initContextMenu(ContextMenu contextMenu) {
	}

	public void updateContextMenu(ContextMenu contextMenu, int characterIndex) {
		ObservableList<MenuItem> menuItems = contextMenu.getItems();

		// remove old menu items
		menuItems.removeAll(menuItems.filtered(menuItem -> CONTEXT_SPELL_PROBLEM_ITEM.equals(menuItem.getUserData())));

		// find problems
		List<SpellProblem> problems = findProblemsAt(characterIndex);
		if (problems.isEmpty())
			return;

		// create menu items
		ArrayList<MenuItem> newItems = new ArrayList<>();
		for (SpellProblem problem : problems) {
			CustomMenuItem problemItem = new SeparatorMenuItem();
			problemItem.setContent(buildMessage(problem.getMessage()));
			problemItem.setUserData(CONTEXT_SPELL_PROBLEM_ITEM);
			newItems.add(problemItem);

			for (String suggestedReplacement : problem.getSuggestedReplacements()) {
				MenuItem item = new MenuItem(suggestedReplacement);
				item.getStyleClass().add("spell-menu-suggestion");
				item.setUserData(CONTEXT_SPELL_PROBLEM_ITEM);
				item.setOnAction(e -> {
					textArea.replaceText(problem.getFromPos(), problem.getToPos(), suggestedReplacement);
				});
				newItems.add(item);
			}
		}

		// add separator (if necessary)
		if (!newItems.isEmpty() && !menuItems.isEmpty()) {
			SeparatorMenuItem separator = new SeparatorMenuItem();
			separator.setUserData(CONTEXT_SPELL_PROBLEM_ITEM);
			newItems.add(separator);
		}

		// add new menu items to context menu
		menuItems.addAll(0, newItems);
	}


	private TextFlow buildMessage(String message) {
		ArrayList<javafx.scene.text.Text> texts = new ArrayList<>();
		Matcher matcher = SUGGESTION_PATTERN.matcher(message);
		int pos = 0;
		while (matcher.find(pos)) {
			int start = matcher.start();
			if (start > pos)
				texts.add(new javafx.scene.text.Text(message.substring(pos, start)));

			javafx.scene.text.Text text = new javafx.scene.text.Text(matcher.group(1));
			text.getStyleClass().add("spell-menu-message-suggestion");
			texts.add(new javafx.scene.text.Text("\""));
			texts.add(text);
			texts.add(new javafx.scene.text.Text("\""));

			pos = matcher.end();
		}
		if (pos < message.length())
			texts.add(new javafx.scene.text.Text(message.substring(pos)));

		TextFlow textFlow = new TextFlow(texts.toArray(new javafx.scene.text.Text[texts.size()])) {
			@Override
			protected double computePrefWidth(double height) {
				// limit width to 300
				return Math.min(super.computePrefWidth(height), 300);
			}
			@Override
			protected double computePrefHeight(double width) {
				// compute height based on maximum width
				return super.computePrefHeight(300);
			}
		};
		textFlow.getStyleClass().add("spell-menu-message");
		return textFlow;
	}

	//---- utility ------------------------------------------------------------

	private List<SpellProblem> findProblemsAt(int index) {
		if (index < 0 || spellProblems == null || spellProblems.isEmpty())
			return Collections.emptyList();

		ArrayList<SpellProblem> result = new ArrayList<>();
		for (SpellBlockProblems blockProblems : spellProblems) {
			if (!blockProblems.contains(index))
				continue;

			for (SpellProblem problem : blockProblems.problems) {
				if (problem.contains(index))
					result.add(problem);
			}
		}
		return result;
	}
}