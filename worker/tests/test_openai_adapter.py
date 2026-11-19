"""Tests for the OpenAI provider adapter.

The HTTP call is hand-rolled with `urllib.request`; the tests mock `urllib.request.urlopen` to
return a pre-built response body, so the adapter is exercised through its real call site
without a network round trip. Regression coverage for the `extra_content.google.thought_signature`
field that an OpenAI-compatible proxy injects is intentional: the OpenAI SDK rejects unknown
fields on parse, so a future rewrite that re-introduces the SDK must keep this assertion green.
"""

from __future__ import annotations

import http.client
import importlib
import io
import json
import time
import urllib.error
import urllib.request
from collections.abc import Callable
from typing import Any, cast
from unittest.mock import MagicMock
from uuid import UUID

import pytest

from flowcortex_worker.errors import PermanentError, TransientError
from flowcortex_worker.provider import openai_adapter
from flowcortex_worker.provider.adapter import SpeechRequest, StoryboardRequest
from flowcortex_worker.provider.contracts.provider_job import ProductAdPayload
from flowcortex_worker.runtime import ProcessKilled, ProcessRunner

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


def test_generate_script_sends_product_image_as_multimodal_input(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured = _patch_urlopen(
        monkeypatch,
        _http_response(_ok_body(json.dumps({"script": "Đúng sản phẩm. Đặt mua ngay"}))),
    )

    _adapter().generate_script(SAMPLE_PAYLOAD, b"\x89PNG\r\n\x1a\nproduct")

    content = captured["body"]["messages"][1]["content"]
    assert content[0]["type"] == "text"
    assert "visual ground truth" in content[0]["text"]
    assert content[1]["type"] == "image_url"
    assert content[1]["image_url"]["url"].startswith("data:image/png;base64,")

def test_generate_storyboard_returns_two_distinct_visual_prompts(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured = _patch_urlopen(
        monkeypatch,
        _http_response(
            _ok_body(
                json.dumps(
                    {
                        "scene1Prompt": "Vertical hero shot with a fast camera push-in.",
                        "scene2Prompt": "Product-in-use shot ending on a clean beauty frame.",
                    }
                )
            )
        ),
    )

    storyboard = _adapter().generate_storyboard(
        StoryboardRequest("Approved Vietnamese script", UUID(int=1))
    )

    assert storyboard.scene_1_prompt.startswith("Vertical hero")
    assert storyboard.scene_2_prompt.startswith("Product-in-use")
    body: dict[str, Any] = captured["body"]
    assert body["response_format"]["json_schema"]["name"] == "product_ad_storyboard_v1"
    assert "Approved Vietnamese script" in body["messages"][1]["content"]


def test_generate_storyboard_sends_product_image_as_multimodal_input(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured = _patch_urlopen(
        monkeypatch,
        _http_response(
            _ok_body(
                json.dumps(
                    {
                        "scene1Prompt": "Vertical hero shot with a fast camera push-in.",
                        "scene2Prompt": "Product-in-use shot ending on a clean beauty frame.",
                    }
                )
            )
        ),
    )

    png = b"\x89PNG\r\n\x1a\nproduct"
    storyboard = _adapter().generate_storyboard(
        StoryboardRequest("Approved Vietnamese script", UUID(int=1)),
        reference_image=png,
    )

    assert storyboard.scene_1_prompt.startswith("Vertical hero")
    content = captured["body"]["messages"][1]["content"]
    assert content[0]["type"] == "text"
    assert "visual ground truth" in content[0]["text"].lower()
    assert content[1]["type"] == "image_url"
    assert content[1]["image_url"]["url"].startswith("data:image/png;base64,")

@pytest.mark.parametrize(
    "provider_content",
    [
        {
            "scene_1_prompt": "Vertical opening hero shot.",
            "scene_2_prompt": "Vertical closing benefit shot.",
        },
        {
            "scenes": [
                {"prompt": "Vertical opening hero shot."},
                {"visualPrompt": "Vertical closing benefit shot."},
            ]
        },
    ],
)
def test_generate_storyboard_accepts_openai_compatible_proxy_shapes(
    monkeypatch: pytest.MonkeyPatch,
    provider_content: dict[str, Any],
) -> None:
    _patch_urlopen(
        monkeypatch,
        _http_response(_ok_body(json.dumps(provider_content))),
    )

    storyboard = _adapter("https://llm-proxy.example/v1").generate_storyboard(
        StoryboardRequest("Approved Vietnamese script", UUID(int=1))
    )

    assert storyboard.scene_1_prompt == "Vertical opening hero shot."
    assert storyboard.scene_2_prompt == "Vertical closing benefit shot."


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


def test_generate_script_rejects_response_that_is_not_json(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    _patch_urlopen(monkeypatch, _http_response(_ok_body("not json")))
    adapter = _adapter()

    with caplog.at_level("WARNING"), pytest.raises(PermanentError, match="response format"):
        adapter.generate_script(SAMPLE_PAYLOAD)

    assert any(getattr(record, "finishReason", None) == "stop" for record in caplog.records)
    assert any(getattr(record, "contentLength", None) == 8 for record in caplog.records)
    assert all("not json" not in record.getMessage() for record in caplog.records)


@pytest.mark.parametrize(
    "wrapped",
    [
        '```json\n{"script": "Mở đầu rồi lợi ích. Đặt mua ngay"}\n```',
        '```\n{"script": "Mở đầu rồi lợi ích. Đặt mua ngay"}\n```',
        '<thought>Phân tích {sản phẩm}</thought>\n{"script": "Mở đầu rồi lợi ích. Đặt mua ngay"}',
        'Dưới đây là kịch bản:\n{"script": "Mở đầu rồi lợi ích. Đặt mua ngay"}\nChúc bạn bán tốt!',
        [{"type": "text", "text": '```json\n{"script": "Mở đầu rồi lợi ích. Đặt mua ngay"}\n```'}],
    ],
)
def test_generate_script_extracts_json_from_proxy_wrapped_content(
    monkeypatch: pytest.MonkeyPatch, wrapped: Any
) -> None:
    _patch_urlopen(monkeypatch, _http_response(_ok_body(wrapped)))

    assert _adapter("https://llm-proxy.example/v1").generate_script(SAMPLE_PAYLOAD) == (
        "Mở đầu rồi lợi ích. Đặt mua ngay"
    )


def test_generate_script_rejects_null_content_without_crashing(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _patch_urlopen(monkeypatch, _http_response(_ok_body(cast(Any, None))))

    with pytest.raises(PermanentError, match="response format"):
        _adapter().generate_script(SAMPLE_PAYLOAD)


@pytest.mark.parametrize(
    ("content", "expected_hint"),
    [
        ("Upstream temporarily unavailable, please retry.", "upstream_temporarily_unavailable"),
        ("Service unavailable, try again later.", "service_unavailable"),
        ("Model overloaded, please retry.", "please_retry"),
        ("Rate limit reached for this route.", "rate_limited"),
    ],
)
def test_generate_script_classifies_masked_200_transient_content_as_transient(
    monkeypatch: pytest.MonkeyPatch,
    caplog: pytest.LogCaptureFixture,
    content: str,
    expected_hint: str,
) -> None:
    body = _ok_body(content)
    body["usage"] = {"prompt_tokens": 0, "completion_tokens": 12, "total_tokens": 12}
    _patch_urlopen(monkeypatch, _http_response(body))

    with caplog.at_level("WARNING"), pytest.raises(TransientError) as raised:
        _adapter("https://llm-proxy.example/v1").generate_script(SAMPLE_PAYLOAD)

    assert raised.value.retry_after_seconds is None
    assert any(
        getattr(record, "matchedHint", None) == expected_hint for record in caplog.records
    )
    assert any(getattr(record, "promptTokens", None) == 0 for record in caplog.records)
    assert all(content not in record.getMessage() for record in caplog.records)


def test_generate_script_rejects_long_prose_even_if_containing_hint_words(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    long_prose = "Please retry again. " * 20
    assert len(long_prose) > 300
    _patch_urlopen(monkeypatch, _http_response(_ok_body(long_prose)))

    with pytest.raises(PermanentError, match="response format"):
        _adapter().generate_script(SAMPLE_PAYLOAD)


@pytest.mark.parametrize(
    ("error_obj", "transient"),
    [
        ({"code": "upstream_error", "message": "Upstream temporarily unavailable"}, True),
        ({"code": "invalid_api_key", "message": "secret token rejected"}, False),
    ],
)
def test_generate_script_classifies_http_200_error_object(
    monkeypatch: pytest.MonkeyPatch,
    caplog: pytest.LogCaptureFixture,
    error_obj: dict[str, str],
    transient: bool,
) -> None:
    _patch_urlopen(monkeypatch, _http_response({"error": error_obj}))
    expected_exc: type[Exception] = TransientError if transient else PermanentError

    with caplog.at_level("WARNING"), pytest.raises(expected_exc):
        _adapter("https://llm-proxy.example/v1").generate_script(SAMPLE_PAYLOAD)

    assert any(
        getattr(record, "providerErrorCode", None) == error_obj["code"]
        for record in caplog.records
    )
    assert all(error_obj["message"] not in record.getMessage() for record in caplog.records)


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
            SpeechRequest("Đặt mua ngay", UUID("a71ea3ba-d72a-4c2c-985c-3a7fd98853c4")),
            MagicMock(),
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


# --- model fallback -------------------------------------------------------------------------

PRIMARY_MODEL = "vgpt/gpt-6.2-sol"
FALLBACK_MODEL = "chib/deepseek-v4.1-flash"


def _fallback_adapter(
    *,
    primary: str = PRIMARY_MODEL,
    fallback: str | None = FALLBACK_MODEL,
    base_url: str = "https://vilao.invalid/v1",
) -> openai_adapter.OpenAIProviderAdapter:
    return openai_adapter.OpenAIProviderAdapter(
        openai_adapter.OpenAIConfig(
            api_key="sk-test",
            base_url=base_url,
            model=primary,
            fallback_model=fallback,
        )
    )


def _raw_http_response(raw: bytes) -> MagicMock:
    response = MagicMock()
    response.read = MagicMock(return_value=raw)
    response.__enter__ = MagicMock(return_value=response)
    response.__exit__ = MagicMock(return_value=False)
    return response


def _http_error(
    status: int,
    payload: dict[str, Any] | bytes,
    *,
    headers: dict[str, str] | None = None,
) -> urllib.error.HTTPError:
    raw = payload if isinstance(payload, bytes) else json.dumps(payload).encode("utf-8")
    return urllib.error.HTTPError(
        "https://vilao.invalid/v1/chat/completions",
        status,
        "HTTP Error",
        cast(Any, headers or {}),
        io.BytesIO(raw),
    )


def _http_error_factory(
    status: int,
    payload: dict[str, Any] | bytes,
    *,
    headers: dict[str, str] | None = None,
) -> Callable[[Any, float], urllib.error.HTTPError]:
    return lambda _request, _timeout: _http_error(status, payload, headers=headers)


def _patch_sequence(
    monkeypatch: pytest.MonkeyPatch, outcomes: list[Any]
) -> list[dict[str, Any]]:
    calls: list[dict[str, Any]] = []
    remaining = list(outcomes)

    def fake_urlopen(request: Any, timeout: float = 0) -> MagicMock:
        calls.append({
            "url": request.full_url,
            "headers": dict(request.headers),
            "body": json.loads(request.data.decode("utf-8")),
            "timeout": timeout,
        })
        outcome = remaining.pop(0)
        if callable(outcome) and not isinstance(outcome, MagicMock):
            outcome = outcome(request, timeout)
        if isinstance(outcome, BaseException):
            raise outcome
        assert isinstance(outcome, MagicMock)
        return outcome

    monkeypatch.setattr(urllib.request, "urlopen", fake_urlopen)
    return calls


def test_openai_config_normalizes_blank_and_duplicate_fallback_model() -> None:
    assert openai_adapter.OpenAIConfig(api_key="k", model=PRIMARY_MODEL).fallback_model is None
    assert (
        openai_adapter.OpenAIConfig(
            api_key="k", model=PRIMARY_MODEL, fallback_model=""
        ).fallback_model
        is None
    )
    assert (
        openai_adapter.OpenAIConfig(
            api_key="k", model=PRIMARY_MODEL, fallback_model="   "
        ).fallback_model
        is None
    )
    assert (
        openai_adapter.OpenAIConfig(
            api_key="k", model=PRIMARY_MODEL, fallback_model=f"  {PRIMARY_MODEL}  "
        ).fallback_model
        is None
    )
    cfg = openai_adapter.OpenAIConfig(
        api_key="k", model=f" {PRIMARY_MODEL} ", fallback_model=f"  {FALLBACK_MODEL}  "
    )
    assert cfg.model == PRIMARY_MODEL
    assert cfg.fallback_model == FALLBACK_MODEL


def test_build_adapter_wires_script_fallback_model_only_for_openai_script(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("SCRIPT_PROVIDER", "openai")
    monkeypatch.setenv("SPEECH_PROVIDER", "openai")
    monkeypatch.setenv("SCRIPT_PROVIDER_API_KEY", "script-key")
    monkeypatch.setenv("SPEECH_PROVIDER_API_KEY", "speech-key")
    monkeypatch.setenv("SCRIPT_PROVIDER_BASE_URL", "https://vilao.invalid/v1")
    monkeypatch.setenv("SCRIPT_PROVIDER_MODEL", PRIMARY_MODEL)
    monkeypatch.setenv("SCRIPT_PROVIDER_FALLBACK_MODEL", f"  {FALLBACK_MODEL}  ")

    from flowcortex_worker.provider import __main__ as provider_main

    importlib.reload(provider_main)
    adapter = provider_main._build_adapter()

    assert isinstance(adapter.script_adapter, openai_adapter.OpenAIProviderAdapter)
    assert isinstance(adapter.speech_adapter, openai_adapter.OpenAIProviderAdapter)
    assert adapter.script_adapter.config.model == PRIMARY_MODEL
    assert adapter.script_adapter.config.fallback_model == FALLBACK_MODEL
    assert adapter.speech_adapter.config.fallback_model is None

    monkeypatch.setenv("SCRIPT_PROVIDER", "fixture")
    fixture_adapter = provider_main._build_adapter()
    assert fixture_adapter.script_adapter.__class__.__name__ == "FixtureProviderAdapter"


def test_generate_script_falls_back_with_json_object_and_preserves_image(
    monkeypatch: pytest.MonkeyPatch,
    caplog: pytest.LogCaptureFixture,
) -> None:
    calls = _patch_sequence(
        monkeypatch,
        [
            _http_error(503, {"error": {"code": "upstream_busy", "message": "overloaded"}}),
            _http_response(_ok_body(json.dumps({"script": "Kịch bản dự phòng. Đặt mua ngay"}))),
        ],
    )
    png = b"\x89PNG\r\n\x1a\nproduct"

    with caplog.at_level("INFO"):
        script = _fallback_adapter().generate_script(SAMPLE_PAYLOAD, png)

    assert script == "Kịch bản dự phòng. Đặt mua ngay"
    assert len(calls) == 2
    assert calls[0]["body"]["model"] == PRIMARY_MODEL
    assert calls[0]["body"]["response_format"]["type"] == "json_schema"
    assert calls[1]["body"]["model"] == FALLBACK_MODEL
    assert calls[1]["body"]["response_format"] == {"type": "json_object"}
    assert "STRICT JSON SCHEMA CONTRACT" in calls[1]["body"]["messages"][0]["content"]
    assert "`script`" in calls[1]["body"]["messages"][0]["content"]
    assert calls[0]["body"]["messages"][1]["content"] == calls[1]["body"]["messages"][1]["content"]
    assert calls[1]["body"]["messages"][1]["content"][1]["type"] == "image_url"
    assert any(
        getattr(r, "primaryModel", None) == PRIMARY_MODEL
        and getattr(r, "fallbackModel", None) == FALLBACK_MODEL
        and getattr(r, "fallbackReason", None) == "upstream_transient"
        and getattr(r, "providerOperation", None) == "generate_script"
        for r in caplog.records
    )


def test_generate_storyboard_falls_back_with_json_object_and_preserves_image(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls = _patch_sequence(
        monkeypatch,
        [
            _http_response(
                _ok_body(json.dumps({"scene1Prompt": "Same shot", "scene2Prompt": "Same shot"}))
            ),
            _http_response(
                _ok_body(
                    json.dumps({
                        "scene1Prompt": "Vertical opening hero shot.",
                        "scene2Prompt": "Vertical lifestyle closing shot.",
                    })
                )
            ),
        ],
    )
    png = b"\x89PNG\r\n\x1a\nproduct"

    storyboard = _fallback_adapter().generate_storyboard(
        StoryboardRequest("Approved Vietnamese script", UUID(int=1)),
        reference_image=png,
    )

    assert storyboard.scene_1_prompt == "Vertical opening hero shot."
    assert storyboard.scene_2_prompt == "Vertical lifestyle closing shot."
    assert len(calls) == 2
    assert calls[0]["body"]["model"] == PRIMARY_MODEL
    assert calls[0]["body"]["response_format"]["type"] == "json_schema"
    assert calls[1]["body"]["model"] == FALLBACK_MODEL
    assert calls[1]["body"]["response_format"] == {"type": "json_object"}
    assert "scene1Prompt" in calls[1]["body"]["messages"][0]["content"]
    assert "scene2Prompt" in calls[1]["body"]["messages"][0]["content"]
    assert calls[0]["body"]["messages"][1]["content"] == calls[1]["body"]["messages"][1]["content"]


@pytest.mark.parametrize(
    "primary_outcome",
    [
        urllib.error.URLError("dns failure"),
        TimeoutError("read timed out"),
        OSError("connection reset"),
        http.client.IncompleteRead(b"partial"),
        _http_error_factory(408, {"error": {"code": "timeout"}}),
        _http_error_factory(429, {"error": {"code": "rate_limit_exceeded"}}),
        _http_error_factory(500, {"error": {"code": "internal_error"}}),
        _http_error_factory(401, {"error": {"code": "invalid_api_key"}}),
        _http_error_factory(403, {"error": {"code": "permission_denied"}}),
        _http_error_factory(400, {"error": {"code": "model_not_found"}}),
        _http_error_factory(404, {"error": {"code": "model_access_denied"}}),
        _http_error_factory(400, {"error": {"type": "model_not_supported"}}),
        _http_response({"error": {"code": "invalid_api_key", "message": "secret"}}),
        _http_response({"choices": None, "error": {"code": "model_not_found", "message": "gone"}}),
        _http_response(
            {"choices": [], "error": {"code": "upstream_error", "message": "please retry"}}
        ),
        _raw_http_response(b"\xff\xfe"),
        _http_response(_ok_body("not json")),
        _http_response(_ok_body(cast(Any, None))),
        _http_response(_ok_body(json.dumps({"script": ""}))),
        _http_response(_ok_body(json.dumps({"script": "a" * 8001}))),
    ],
)
def test_fallback_triggers_on_eligible_failures(
    monkeypatch: pytest.MonkeyPatch, primary_outcome: Any
) -> None:
    calls = _patch_sequence(
        monkeypatch,
        [
            primary_outcome,
            _http_response(_ok_body(json.dumps({"script": "Fallback OK. Đặt mua ngay"}))),
        ],
    )

    result = _fallback_adapter().generate_script(SAMPLE_PAYLOAD)

    assert result == "Fallback OK. Đặt mua ngay"
    assert [c["body"]["model"] for c in calls] == [PRIMARY_MODEL, FALLBACK_MODEL]


@pytest.mark.parametrize(
    "primary_outcome",
    [
        _http_error_factory(400, {"error": {"code": "bad_request", "message": "bad param"}}),
        _http_response({"error": {"code": "bad_request", "message": "bad param"}}),
        _http_error_factory(400, {"error": {"code": "content_policy_violation"}}),
        _http_error_factory(401, {"error": {"code": "content_policy_violation"}}),
        _http_error_factory(403, {"error": {"type": "moderation_blocked"}}),
        _http_response({
            "choices": [],
            "error": {
                "code": "content_policy_violation",
                "message": "Upstream temporarily unavailable, please retry.",
            },
        }),
        _http_response({
            "id": "t",
            "choices": [
                {
                    "index": 0,
                    "finish_reason": "content_filter",
                    "message": {"role": "assistant", "content": None},
                }
            ],
        }),
        _http_response({
            "id": "t",
            "choices": [
                {
                    "index": 0,
                    "finish_reason": "stop",
                    "message": {
                        "role": "assistant",
                        "content": None,
                        "refusal": "secret policy refusal text",
                    },
                }
            ],
        }),
    ],
)
def test_fallback_stops_on_terminal_moderation_refusal_and_bad_request(
    monkeypatch: pytest.MonkeyPatch,
    caplog: pytest.LogCaptureFixture,
    primary_outcome: Any,
) -> None:
    calls = _patch_sequence(
        monkeypatch,
        [
            primary_outcome,
            _http_response(_ok_body(json.dumps({"script": "should not be reached"}))),
        ],
    )

    with caplog.at_level("WARNING"), pytest.raises(PermanentError) as raised:
        _fallback_adapter().generate_script(SAMPLE_PAYLOAD)

    assert raised.value.code == "provider_request_failed"
    assert len(calls) == 1
    assert all("secret policy refusal text" not in r.getMessage() for r in caplog.records)


def test_fallback_stops_on_invalid_input_image_and_abort(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls = _patch_sequence(monkeypatch, [])
    with pytest.raises(PermanentError) as raised:
        _fallback_adapter().generate_script(SAMPLE_PAYLOAD, b"not-an-image")
    assert raised.value.code == "input_rejected"
    assert calls == []

    killed_runner = ProcessRunner()
    killed_runner.kill("shutdown")
    with pytest.raises(ProcessKilled, match="shutdown"):
        _fallback_adapter().generate_script(SAMPLE_PAYLOAD, abort=killed_runner)
    assert calls == []

    mid_runner = ProcessRunner()

    def kill_during_primary(request: Any, timeout: float) -> Any:
        mid_runner.kill("shutdown")
        raise _http_error(503, {"error": {"code": "upstream_busy"}})

    mid_calls = _patch_sequence(
        monkeypatch,
        [
            kill_during_primary,
            _http_response(_ok_body(json.dumps({"script": "should not run"}))),
        ],
    )
    with pytest.raises(ProcessKilled, match="shutdown"):
        _fallback_adapter().generate_script(SAMPLE_PAYLOAD, abort=mid_runner)
    assert len(mid_calls) == 1


def test_fallback_stops_after_one_attempt_and_surfaces_last_failure(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls_perm = _patch_sequence(
        monkeypatch,
        [
            _http_error(503, {"error": {"code": "upstream_busy"}}),
            _http_error(400, {"error": {"code": "bad_request"}}),
        ],
    )
    with pytest.raises(PermanentError) as perm:
        _fallback_adapter().generate_script(SAMPLE_PAYLOAD)
    assert perm.value.code == "provider_request_failed"
    assert len(calls_perm) == 2

    calls_trans = _patch_sequence(
        monkeypatch,
        [
            _http_response(_ok_body("not json")),
            _http_error(503, {"error": {"code": "upstream_busy"}}),
        ],
    )
    with pytest.raises(TransientError):
        _fallback_adapter().generate_script(SAMPLE_PAYLOAD)
    assert len(calls_trans) == 2


def test_fallback_timeout_budgets_and_legacy_single_model_timeout(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(openai_adapter, "_REQUEST_TIMEOUT_SECONDS", 60.0)
    now = 1000.0
    monkeypatch.setattr(time, "monotonic", lambda: now)

    # Single-model keeps legacy timeout unchanged even with a deadline.
    single_calls = _patch_sequence(
        monkeypatch,
        [_http_response(_ok_body(json.dumps({"script": "Single model OK"})))],
    )
    _fallback_adapter(fallback=None).generate_script(
        SAMPLE_PAYLOAD, deadline_monotonic=now + 30.0
    )
    assert single_calls[0]["timeout"] == 60.0

    # Fallback without deadline caps both calls at min(45, configured).
    no_deadline_calls = _patch_sequence(
        monkeypatch,
        [
            _http_error(503, {"error": {"code": "upstream_busy"}}),
            _http_response(_ok_body(json.dumps({"script": "Fallback OK"}))),
        ],
    )
    _fallback_adapter().generate_script(SAMPLE_PAYLOAD)
    assert [c["timeout"] for c in no_deadline_calls] == [45.0, 45.0]

    # With deadline (remaining=30s -> usable=20s): primary=10s, fallback=recalculated usable.
    clock = [1000.0]
    monkeypatch.setattr(time, "monotonic", lambda: clock[0])

    def slow_primary(request: Any, timeout: float) -> Any:
        clock[0] += 5.0
        raise _http_error(503, {"error": {"code": "upstream_busy"}})

    split_calls = _patch_sequence(
        monkeypatch,
        [
            slow_primary,
            _http_response(_ok_body(json.dumps({"script": "Fallback OK"}))),
        ],
    )
    _fallback_adapter().generate_script(SAMPLE_PAYLOAD, deadline_monotonic=1030.0)
    assert split_calls[0]["timeout"] == pytest.approx(10.0)
    assert split_calls[1]["timeout"] == pytest.approx(15.0)

    # Usable in [1s, 2s): given to primary without splitting; if fallback usable < 1s, skip.
    clock[0] = 1000.0

    def quick_primary(request: Any, timeout: float) -> Any:
        clock[0] += 0.8
        raise _http_error(503, {"error": {"code": "upstream_busy"}})

    unsplit_calls = _patch_sequence(
        monkeypatch,
        [
            quick_primary,
            _http_response(_ok_body(json.dumps({"script": "should be skipped"}))),
        ],
    )
    with pytest.raises(TransientError, match="HTTP 503"):
        _fallback_adapter().generate_script(SAMPLE_PAYLOAD, deadline_monotonic=1011.5)
    assert len(unsplit_calls) == 1
    assert unsplit_calls[0]["timeout"] == pytest.approx(1.5)

    # Usable < 1s before primary: PermanentError("run_timeout") without any network call.
    clock[0] = 1000.0
    expired_calls = _patch_sequence(monkeypatch, [])
    with pytest.raises(PermanentError) as timeout_err:
        _fallback_adapter().generate_script(SAMPLE_PAYLOAD, deadline_monotonic=1010.5)
    assert timeout_err.value.code == "run_timeout"
    assert expired_calls == []


def test_http_error_read_is_bounded_and_closed_and_sanitizes_tokens(
    monkeypatch: pytest.MonkeyPatch,
    caplog: pytest.LogCaptureFixture,
) -> None:
    read_sizes: list[int | None] = []
    closed = False

    class TrackedStream(io.BytesIO):
        def read(self, size: int | None = -1, /) -> bytes:
            read_sizes.append(size)
            return super().read(size)

        def close(self) -> None:
            nonlocal closed
            closed = True
            super().close()

    body = json.dumps({
        "error": {"code": "unsanitized secret http://leak.invalid", "message": "raw detail"}
    }).encode("utf-8")
    err = urllib.error.HTTPError(
        "https://vilao.invalid/v1/chat/completions",
        400,
        "Bad Request",
        cast(Any, {}),
        TrackedStream(body),
    )
    _patch_sequence(monkeypatch, [err])

    with caplog.at_level("WARNING"), pytest.raises(PermanentError):
        _fallback_adapter().generate_script(SAMPLE_PAYLOAD)

    assert read_sizes == [openai_adapter._MAX_ERROR_BODY_BYTES]
    assert closed is True
    assert any(getattr(r, "providerErrorCode", None) == "unknown" for r in caplog.records)
    assert all("http://leak.invalid" not in r.getMessage() for r in caplog.records)
    assert all("raw detail" not in r.getMessage() for r in caplog.records)


def test_unsanitized_finish_reason_and_prompt_tokens_are_not_logged_verbatim(
    monkeypatch: pytest.MonkeyPatch,
    caplog: pytest.LogCaptureFixture,
) -> None:
    body = _ok_body("not json")
    body["choices"][0]["finish_reason"] = "untrusted-secret-reason"
    body["usage"] = {"prompt_tokens": "secret-token-value"}
    _patch_sequence(monkeypatch, [_http_response(body)])

    with caplog.at_level("WARNING"), pytest.raises(PermanentError):
        _adapter().generate_script(SAMPLE_PAYLOAD)

    invalid_records = [
        r
        for r in caplog.records
        if r.getMessage() == "provider returned invalid structured content"
    ]
    assert len(invalid_records) == 1
    assert getattr(invalid_records[0], "finishReason", None) == "unknown"
    assert getattr(invalid_records[0], "promptTokens", "sentinel") is None
