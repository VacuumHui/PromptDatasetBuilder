package com.promptdatasetbuilder.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CivitaiResponseParserTest {
    @Test
    fun parsesTrpcFeedAndCursor() {
        val json = """
            {
              "result": {
                "data": {
                  "json": {
                    "items": [
                      {
                        "id": 123456,
                        "url": "https://image.civitai.com/example.png",
                        "width": 832,
                        "height": 1216,
                        "nsfwLevel": 1,
                        "user": {"username": "author"}
                      }
                    ],
                    "nextCursor": 123455
                  }
                }
              }
            }
        """.trimIndent()

        val page = CivitaiResponseParser.parseFeed(json)
        assertEquals(1, page.items.size)
        assertEquals("123456", page.items.single().id)
        assertEquals("author", page.items.single().username)
        assertEquals("123455", page.nextCursor)
    }

    @Test
    fun parsesPromptFromGenerationData() {
        val json = """
            {
              "result": {
                "data": {
                  "json": {
                    "meta": {
                      "prompt": "cinematic portrait, rim light",
                      "negativePrompt": "blurry"
                    }
                  }
                }
              }
            }
        """.trimIndent()

        assertEquals(
            "cinematic portrait, rim light",
            CivitaiResponseParser.parseGenerationPrompt(json),
        )
    }

    @Test
    fun returnsNullWhenGenerationDataHasNoPrompt() {
        val json = """
            {"result":{"data":{"json":{"meta":null}}}}
        """.trimIndent()
        assertNull(CivitaiResponseParser.parseGenerationPrompt(json))
    }

    @Test
    fun detectsHtmlBeforeJsonParsing() {
        assertTrue(CivitaiResponseParser.looksLikeHtml("<!DOCTYPE html><html></html>"))
    }
}
