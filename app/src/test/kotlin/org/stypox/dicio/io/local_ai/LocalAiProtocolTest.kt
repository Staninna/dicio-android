package org.stypox.dicio.io.local_ai

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.stypox.dicio.io.local_ai.LocalAiException.Kind
import org.stypox.dicio.io.local_ai.LocalAiProtocol.Message
import org.stypox.dicio.io.local_ai.LocalAiProtocol.StreamEvent

class LocalAiProtocolTest : StringSpec({
    "request body is a streaming chat completion without thinking" {
        val body = Json.parseToJsonElement(
            LocalAiProtocol.chatRequestBody(
                listOf(Message("system", "Be brief"), Message("user", "Hoe laat is \"het\"?")),
                maxTokens = 256,
            )
        ).jsonObject
        body["stream"]!!.jsonPrimitive.boolean shouldBe true
        body["max_tokens"]!!.jsonPrimitive.int shouldBe 256
        body["chat_template_kwargs"]!!.jsonObject["enable_thinking"]!!.jsonPrimitive.boolean shouldBe false
        val messages = body["messages"]!!.jsonArray
        messages.size shouldBe 2
        messages[1].jsonObject["role"]!!.jsonPrimitive.content shouldBe "user"
        messages[1].jsonObject["content"]!!.jsonPrimitive.content shouldBe "Hoe laat is \"het\"?"
    }

    "stream lines" {
        LocalAiProtocol.parseStreamLine(
            """data: {"id":"c","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"Today I"},"finish_reason":null}]}"""
        ) shouldBe StreamEvent.Delta("Today I")
        LocalAiProtocol.parseStreamLine(
            """data: {"choices":[{"index":0,"delta":{"role":"assistant"},"finish_reason":null}]}"""
        ) shouldBe StreamEvent.Nothing
        LocalAiProtocol.parseStreamLine(
            """data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":5}}"""
        ) shouldBe StreamEvent.Nothing
        LocalAiProtocol.parseStreamLine("data: [DONE]") shouldBe StreamEvent.Done
        LocalAiProtocol.parseStreamLine(": keep-alive") shouldBe StreamEvent.Nothing
        LocalAiProtocol.parseStreamLine("") shouldBe StreamEvent.Nothing
        LocalAiProtocol.parseStreamLine("data: not json") shouldBe StreamEvent.Nothing
        LocalAiProtocol.parseStreamLine(
            """data: {"error":{"message":"Stopped","type":"server_error","code":"cancelled"}}"""
        ) shouldBe StreamEvent.Error("cancelled", "Stopped")
    }

    "health status" {
        LocalAiProtocol.parseHealthStatus("""{"status":"idle","api_version":3,"features":["wake"]}""") shouldBe "idle"
        LocalAiProtocol.parseHealthStatus("garbage") shouldBe null
    }

    "error responses" {
        val unauthorized = LocalAiProtocol.errorFromResponse(
            401, """{"error":{"message":"Pair again","type":"authentication_error","code":"unauthorized"}}"""
        )
        unauthorized.kind shouldBe Kind.UNAUTHORIZED
        unauthorized.message shouldBe "Pair again"
        LocalAiProtocol.errorFromResponse(429, null).kind shouldBe Kind.BUSY
        LocalAiProtocol.errorFromResponse(503, "{}").kind shouldBe Kind.NO_MODEL
        LocalAiProtocol.errorFromResponse(400, """{"error":{"code":"context_length_exceeded"}}""").kind shouldBe Kind.TOO_LONG
        LocalAiProtocol.errorFromResponse(500, "oops").apply {
            kind shouldBe Kind.FAILED
            message!! shouldContain "500"
        }
    }

    "sentences are split as they complete" {
        val splitter = SentenceSplitter()
        splitter.add("Amsterdam is the capital") shouldBe listOf()
        splitter.add(" of the Netherlands. It has") shouldBe listOf("Amsterdam is the capital of the Netherlands.")
        // a number with a decimal point is not the end of a sentence
        splitter.add(" about 0.9 million inhabitants! Dr. Smith") shouldBe listOf("It has about 0.9 million inhabitants!")
        // "Dr." is too short to be a sentence of its own
        splitter.add(" said so. ") shouldBe listOf("Dr. Smith said so.")
        splitter.add("Bye") shouldBe listOf()
        splitter.flush() shouldBe "Bye"
        splitter.flush() shouldBe null
    }

    "newlines end sentences" {
        SentenceSplitter().add("Yes\nNo") shouldBe listOf("Yes")
    }
})
