"""Tests for the OpenAI provider adapter.

The HTTP call is hand-rolled with `urllib.request`; the tests mock `urllib.request.urlopen` to
return a pre-built response body, so the adapter is exercised through its real call site
without a network round trip. Regression coverage for the `extra_content.google.thought_signature`
field that an OpenAI-compatible proxy injects is intentional: the OpenAI SDK rejects unknown
fields on parse, so a future rewrite that re-introduces the SDK must keep this assertion green.
"""

from __future__ import annotations

import importlib
import io
import json
import urllib.error
import urllib.request
from typing import Any, cast
from unittest.mock import MagicMock
from uuid import UUID

import pytest

from flowcortex_worker.errors import PermanentError, TransientError
from flowcortex_worker.provider import openai_adapter
from flowcortex_worker.provider.adapter import SpeechRequest
from flowcortex_worker.provider.contracts.provider_job import ProductAdPayload

SAMPLE_PAYLOAD = ProductAdPayload(
    productName="Bình giữ nhiệt",
    description="Giữ đồ uống tiện lợi",
    cta="Đặt mua ngay",
)


def _adapter(base_url: str = "https://example.invalid/v1") -> openai_adapter.OpenAIProviderAdapter:
    return openai_adapter.OpenAIProviderAdapter(
        openai_adapter.OpenAIConfig(api_key="sk-test", base_url=base_url)
    )


def _http_response(body: dict[str, Any]) -> MagicMock:
    """Stand-in for an `http.client.HTTPResponse` returned by `urllib.request.urlopen`."""
    response = MagicMock()
    response.read = MagicMock(return_value=json.dumps(body).encode("utf-8"))
    response.__enter__ = MagicMock(return_value=response)
    response.__exit__ = MagicMock(return_value=False)
    return response


def _ok_body(content: str) -> dict[str, Any]:
    return {
        "id": "test",
        "object": "chat.completion",
        "created": 0,
        "model": "test",
        "choices": [
            {
                "index": 0,
                "finish_reason": "stop",
                "message": {"role": "assistant", "content": content},
            }
        ],
    }


def _patch_urlopen(monkeypatch: pytest.MonkeyPatch, response: MagicMock) -> dict[str, Any]:
    """Replace `urllib.request.urlopen` with a fake that records the request."""
    captured: dict[str, Any] = {}

    def fake_urlopen(request: Any, timeout: float = 0) -> MagicMock:
        captured["request"] = request
        captured["timeout"] = timeout
        captured["body"] = json.loads(request.data.decode("utf-8"))
        captured["headers"] = dict(request.headers)
        captured["url"] = request.full_url
        return response

    monkeypatch.setattr(urllib.request, "urlopen", fake_urlopen)
    return captured


def test_generate_script_uses_response_format_json_schema(monkeypatch: pytest.MonkeyPatch) -> None:
    captured = _patch_urlopen(
        monkeypatch,
        _http_response(_ok_body(json.dumps({"script": "Mở đầu rồi lợi ích. Đặt mua ngay"}))),
    )

    adapter = _adapter()

    script = adapter.generate_script(SAMPLE_PAYLOAD)

    assert script == "Mở đầu rồi lợi ích. Đặt mua ngay"
    body: dict[str, Any] = captured["body"]
    assert body["model"] == openai_adapter._OPENAI_SCRIPT_MODEL
    assert captured["url"] == "https://example.invalid/v1/chat/completions"
    assert captured["headers"]["Authorization"] == "Bearer sk-test"
    response_format: dict[str, Any] = body["response_format"]
    assert response_format["type"] == "json_schema"
    schema: dict[str, Any] = response_format["json_schema"]
    assert schema["strict"] is True
    schema_properties: dict[str, Any] = schema["schema"]["properties"]
    assert schema["schema"]["required"] == ["script"]
    assert "description" in schema_properties["script"]
    messages: list[dict[str, Any]] = body["messages"]
    assert messages[0]["role"] == "system"
    assert "Vietnamese" in messages[0]["content"]
    assert "productName: Bình giữ nhiệt" in messages[1]["content"]
    assert "cta: Đặt mua ngay" in messages[1]["content"]


def test_generate_script_ignores_proxy_extra_content(monkeypatch: pytest.MonkeyPatch) -> None:
    """A Google-style `extra_content` block (added by an OpenAI-compatible proxy) must not break
    parsing — the adapter reads only `choices[0].message.content`."""
    body = _ok_body(json.dumps({"script": "Hook. Lợi ích. Đặt mua ngay"}))
    body["choices"][0]["message"]["extra_content"] = {
        "google": {"thought_signature": "EmkKZwFpFH0TF1Uvm5uSzlFg..."}
    }
    _patch_urlopen(monkeypatch, _http_response(body))

    adapter = _adapter("https://llm-proxy.example/v1")

    assert adapter.generate_script(SAMPLE_PAYLOAD) == "Hook. Lợi ích. Đặt mua ngay"


def test_generate_script_quotes_confirmed_price_and_promotion_only(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, list[Any]] = {"bodies": []}
    response = _http_response(_ok_body(json.dumps({"script": "Khám phá Bình giữ nhiệt."})))

    def fake_urlopen(request: Any, timeout: float = 0) -> MagicMock:
        captured["bodies"].append(json.loads(request.data.decode("utf-8")))
        return response

    monkeypatch.setattr(urllib.request, "urlopen", fake_urlopen)

    adapter = _adapter()

    priced = ProductAdPayload(
        productName="Bình giữ nhiệt",
        description="Giữ đồ uống tiện lợi",
        cta="Đặt mua ngay",
        price="299.000đ",
        promotion="Giảm 10% hôm nay",
    )
    adapter.generate_script(priced)
    user = captured["bodies"][0]["messages"][1]["content"]
    assert "price: 299.000đ" in user
    assert "promotion: Giảm 10% hôm nay" in user

    bare = ProductAdPayload(
        productName="Bình giữ nhiệt",
        description="Giữ đồ uống tiện lợi",
        cta="Đặt mua ngay",
    )
    adapter.generate_script(bare)
    user = captured["bodies"][1]["messages"][1]["content"]
    assert "price" not in user
    assert "promotion" not in user


def test_generate_script_rejects_an_empty_payload(monkeypatch: pytest.MonkeyPatch) -> None:
    _patch_urlopen(monkeypatch, _http_response(_ok_body(json.dumps({"script": ""}))))
    adapter = _adapter()

    with pytest.raises(PermanentError, match="empty"):
        adapter.generate_script(SAMPLE_PAYLOAD)


def test_generate_script_rejects_oversize_payload_at_boundary(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    too_long = "a" * 8001
    _patch_urlopen(monkeypatch, _http_response(_ok_body(json.dumps({"script": too_long}))))
    adapter = _adapter()

    with pytest.raises(PermanentError, match="exceeds 8000"):
        adapter.generate_script(SAMPLE_PAYLOAD)


def test_generate_script_rejects_response_that_is_not_json(monkeypatch: pytest.MonkeyPatch) -> None:
    _patch_urlopen(monkeypatch, _http_response(_ok_body("not json")))
    adapter = _adapter()

    with pytest.raises(PermanentError, match="response format"):
        adapter.generate_script(SAMPLE_PAYLOAD)


@pytest.mark.parametrize("status", [400, 401, 403])
def test_generate_script_classifies_non_retryable_http_error_without_logging_body(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture, status: int
) -> None:
    body = b'{"error":{"code":"bad_request","message":"schema rejected"}}'

    def fake_urlopen(request: Any, timeout: float = 0) -> MagicMock:
        raise urllib.error.HTTPError(
            request.full_url, status, "Rejected", cast(Any, {}), io.BytesIO(body)
        )

    monkeypatch.setattr(urllib.request, "urlopen", fake_urlopen)
    adapter = _adapter()

    with pytest.raises(PermanentError) as raised:
        adapter.generate_script(SAMPLE_PAYLOAD)
    assert raised.value.code == "provider_request_failed"
    assert any(getattr(record, "status", None) == status for record in caplog.records)
    assert any(
        getattr(record, "providerErrorCode", None) == "bad_request" for record in caplog.records
    )
    assert all("schema rejected" not in record.getMessage() for record in caplog.records)


@pytest.mark.parametrize("status", [408, 429, 500, 503])
def test_generate_script_classifies_retryable_http_errors(
    monkeypatch: pytest.MonkeyPatch, status: int
) -> None:
    headers = {"Retry-After": "17"} if status == 429 else {}

    def fake_urlopen(request: Any, timeout: float = 0) -> MagicMock:
        raise urllib.error.HTTPError(
            request.full_url,
            status,
            "upstream failure",
            cast(Any, headers),
            io.BytesIO(b'{"error":{"code":"upstream_busy","message":"secret detail"}}'),
        )

    monkeypatch.setattr(urllib.request, "urlopen", fake_urlopen)
    with pytest.raises(TransientError) as raised:
        _adapter().generate_script(SAMPLE_PAYLOAD)

    expected = 17.0 if status == 429 else None
    assert raised.value.retry_after_seconds == expected


def test_select_adapter_requires_openai_key_for_openai_provider() -> None:
    with pytest.raises(RuntimeError, match="OPENAI_API_KEY"):
        openai_adapter.select_adapter(openai_adapter.PROVIDER_OPENAI, "")


def test_generate_speech_fails_instead_of_returning_an_empty_artifact() -> None:
    adapter = _adapter()

    with pytest.raises(PermanentError) as raised:
        adapter.generate_speech(
            SpeechRequest("Đặt mua ngay", UUID("a71ea3ba-d72a-4c2c-985c-3a7fd98853c4"))
        )

    assert raised.value.code == "provider_capability_unavailable"


def test_select_adapter_rejects_unknown_provider() -> None:
    with pytest.raises(ValueError, match="Unknown provider"):
        openai_adapter.select_adapter("anthropic", "k")


def test_build_adapter_rejects_an_unrecognised_provider(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("PROVIDER_ADAPTER", "opnai")
    monkeypatch.delenv("SCRIPT_PROVIDER", raising=False)
    monkeypatch.delenv("SPEECH_PROVIDER", raising=False)
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)

    from flowcortex_worker.provider import __main__ as provider_main

    importlib.reload(provider_main)
    with pytest.raises(RuntimeError, match=r"SCRIPT_PROVIDER.*opnai.*fixture, openai"):
        provider_main._build_adapter()


def test_build_adapter_defaults_to_fixture(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("PROVIDER_ADAPTER", raising=False)
    monkeypatch.delenv("SCRIPT_PROVIDER", raising=False)
    monkeypatch.delenv("SPEECH_PROVIDER", raising=False)

    from flowcortex_worker.provider import __main__ as provider_main

    importlib.reload(provider_main)
    adapter = provider_main._build_adapter()
    assert adapter.script_adapter.__class__.__name__ == "FixtureProviderAdapter"
    assert adapter.speech_adapter.__class__.__name__ == "FixtureProviderAdapter"


def test_build_adapter_uses_openai_when_provider_is_openai(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("PROVIDER_ADAPTER", "openai")
    monkeypatch.delenv("SCRIPT_PROVIDER", raising=False)
    monkeypatch.delenv("SPEECH_PROVIDER", raising=False)
    monkeypatch.setenv("OPENAI_API_KEY", "sk-test")

    from flowcortex_worker.provider import __main__ as provider_main

    importlib.reload(provider_main)
    adapter = provider_main._build_adapter()
    assert isinstance(adapter.script_adapter, openai_adapter.OpenAIProviderAdapter)
    assert isinstance(adapter.speech_adapter, openai_adapter.OpenAIProviderAdapter)
    assert adapter.script_adapter.config.api_key == "sk-test"
    assert adapter.script_adapter.config.base_url == openai_adapter._OPENAI_BASE_URL


def test_build_adapter_routes_openai_script_to_edge_speech(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("PROVIDER_ADAPTER", "fixture")
    monkeypatch.setenv("SCRIPT_PROVIDER", "openai")
    monkeypatch.setenv("SPEECH_PROVIDER", "edge")
    monkeypatch.setenv("OPENAI_API_KEY", "sk-test")
    monkeypatch.setenv("SCRIPT_PROVIDER_BASE_URL", "https://third-party.example/v1")
    monkeypatch.setenv("SCRIPT_PROVIDER_MODEL", "vendor/model")

    from flowcortex_worker.provider import __main__ as provider_main
    from flowcortex_worker.provider.edge_adapter import EdgeSpeechAdapter

    importlib.reload(provider_main)
    adapter = provider_main._build_adapter()

    assert isinstance(adapter.script_adapter, openai_adapter.OpenAIProviderAdapter)
    assert isinstance(adapter.speech_adapter, EdgeSpeechAdapter)
    assert adapter.script_adapter.config.base_url == "https://third-party.example/v1"
    assert adapter.script_adapter.config.model == "vendor/model"


def test_build_adapter_keeps_capability_api_keys_separate(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("SCRIPT_PROVIDER", "openai")
    monkeypatch.setenv("SPEECH_PROVIDER", "openai")
    monkeypatch.setenv("SCRIPT_PROVIDER_API_KEY", "script-key")
    monkeypatch.setenv("SPEECH_PROVIDER_API_KEY", "speech-key")
    monkeypatch.setenv("SCRIPT_PROVIDER_BASE_URL", "https://script.example/v1")
    monkeypatch.setenv("SPEECH_PROVIDER_BASE_URL", "https://speech.example/v1")
    monkeypatch.setenv("SCRIPT_PROVIDER_MODEL", "script-model")
    monkeypatch.setenv("SPEECH_PROVIDER_MODEL", "speech-model")
    monkeypatch.setenv("OPENAI_API_KEY", "legacy-key")

    from flowcortex_worker.provider import __main__ as provider_main

    importlib.reload(provider_main)
    adapter = provider_main._build_adapter()

    assert isinstance(adapter.script_adapter, openai_adapter.OpenAIProviderAdapter)
    assert isinstance(adapter.speech_adapter, openai_adapter.OpenAIProviderAdapter)
    assert adapter.script_adapter.config.api_key == "script-key"
    assert adapter.speech_adapter.config.api_key == "speech-key"
    assert adapter.script_adapter.config.base_url == "https://script.example/v1"
    assert adapter.speech_adapter.config.base_url == "https://speech.example/v1"
    assert adapter.script_adapter.config.model == "script-model"
    assert adapter.speech_adapter.config.model == "speech-model"
