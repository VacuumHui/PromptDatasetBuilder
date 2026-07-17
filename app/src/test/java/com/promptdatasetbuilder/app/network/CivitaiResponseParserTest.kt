package com.promptdatasetbuilder.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CivitaiResponseParserTest {
    @Test
    fun parsesOfficialImageResponseAndPrompt() {
        val json = """
            {
              "items": [
                {
                  "id": 123,
                  "url": "https://image.civitai.com/example.jpeg",
                  "width": 1024,
                  "height": 1536,
                  "nsfwLevel": "None",
                  "username": "tester",
                  "meta": {
                    "prompt": "cinematic portrait, soft light",
                    "negativePrompt": "blurry"
                  }
                },
                {
                  "id": 124,
                  "url": "https://image.civitai.com/no-prompt.jpeg",
                  "width": 512,
                  "height": 512,
                  "meta": null
                }
              ],
              "metadata": {
                "nextCursor": "123|456",
                "nextPage": "https://civitai.com/api/v1/images?limit=20&cursor=123%7C456"
              }
            }
        """.trimIndent()

        val page = CivitaiResponseParser.parse(json)

        assertEquals(2, page.rawItemCount)
        assertEquals(1, page.items.size)
        assertEquals("123", page.items.first().id)
        assertEquals("cinematic portrait, soft light", page.items.first().prompt)
        assertEquals("123|456", page.nextCursor)
    }

    @Test
    fun recognizesHtmlInsteadOfJson() {
        assertTrue(CivitaiResponseParser.looksLikeHtml("<!DOCTYPE html><html></html>"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsHtmlResponse() {
        CivitaiResponseParser.parse("<!DOCTYPE html><html></html>")
    }
}
