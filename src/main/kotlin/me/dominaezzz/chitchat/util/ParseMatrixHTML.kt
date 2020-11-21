package me.dominaezzz.chitchat.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.annotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

fun parseMatrixCustomHtml(htmlText: String, maxDepth: Int = 100): AnnotatedString {
	val html = Jsoup.parseBodyFragment(htmlText).body()
	return annotatedString { appendChildren(html, maxDepth) }
}

private fun AnnotatedString.Builder.appendElement(element: Element, maxDepth: Int) {
	if (maxDepth <= 0) return

	// https://matrix.org/docs/spec/client_server/r0.6.0#id328

	// font, h1, h2, h3, h4, h5, h6, p, ul, ol, li, img

	// Must be down outside
	// blockquote, hr, div
	// table, thead, tbody, tr, th, td, caption

	when (element.normalName()) {
		"br" -> append("\n")
		"i", "em" -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendChildren(element, maxDepth) }
		"b", "strong" -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendChildren(element, maxDepth) }
		"del", "strike" -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendChildren(element, maxDepth) }
		"pre" /* TBD */ -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { appendChildren(element, maxDepth) }
		"code" -> {
			// language
			withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { appendChildren(element, maxDepth) }
		}
		"sup" -> withStyle(SpanStyle(baselineShift = BaselineShift.Superscript)) { appendChildren(element, maxDepth) }
		"sub" -> withStyle(SpanStyle(baselineShift = BaselineShift.Subscript)) { appendChildren(element, maxDepth) }
		"a" -> withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
			pushStringAnnotation("UNDEFINED", element.attr("href"))
			appendChildren(element, maxDepth)
			pop()
		}
		"u" -> {
			// TODO: This should be a squiggly underline. https://developer.mozilla.org/en-US/docs/Web/HTML/Element/u
			withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { appendChildren(element, maxDepth) }
		}
		"span" -> {
			val mxColor = element.attr("data-mx-color")
			val mxBgColor = element.attr("data-mx-bg-color")
			val style = SpanStyle(
				color = parseColor(mxColor) ?: Color.Unspecified,
				background = parseColor(mxBgColor) ?: Color.Unspecified
			)
			withStyle(style) {
				appendChildren(element, maxDepth)
			}
		}
		else -> appendChildren(element, maxDepth) // TODO: Need to properly handle this.
	}
}

private fun AnnotatedString.Builder.appendChildren(parent: Element, maxDepth: Int) {
	for (node in parent.childNodes()) {
		when (node) {
			is TextNode -> append(node.text())
			is Element -> appendElement(node, maxDepth - 1)
		}
	}
}

private fun parseColor(color: String): Color? {
	if (color.length != 6) return null
	return color.toIntOrNull(16)?.let { Color(it).copy(alpha = 1f) }
}
