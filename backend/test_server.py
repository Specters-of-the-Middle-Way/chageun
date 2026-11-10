import io
import json
import os
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch
import urllib.error
import urllib.request

import server


def request_data():
    return {"utterance": "미러에서 뭘 봐야 해?", "history": [], "context": {
        "topic": "LANE_CHANGE", "mode": "SDK", "direction": "UNKNOWN", "awaitingDirection": False,
        "awaitingConfirmation": False, "reportedTraffic": None, "vehicle": {"source": "SDK", "speedKmh": {"value": 0.0, "ageMs": 0}},
        "navigation": None, "limitations": ["NO_CAMERA", "NO_ENTRY_PERMISSION"]}}


class RelayTests(unittest.TestCase):
    def test_payload_uses_current_context_and_bounded_history_as_data_without_storage_or_tools(self):
        data = request_data()
        data["history"] = [{"role": "user", "text": "옆이 꽉 찼어요", "ageMs": 100, "source": "PLEOS_STT"}]
        body = server.request_payload(server.validate_request(data), "gpt-5.4-mini", "none")
        self.assertFalse(body["store"])
        self.assertNotIn("tools", body)
        self.assertEqual(data, json.loads(body["input"][0]["content"]))
        self.assertEqual("json_schema", body["text"]["format"]["type"])
        self.assertTrue(body["text"]["format"]["strict"])

    def test_no_key_is_explicit_failure_not_fake_llm(self):
        with patch.dict(os.environ, {"OPENAI_API_KEY": ""}):
            with self.assertRaisesRegex(server.RelayError, "API_KEY_MISSING"):
                server.call_openai(request_data())

    def test_real_openai_response_shape_is_decoded(self):
        result = server.fixture_response(request_data())["result"]
        payload = {"status": "completed", "output": [{"type": "reasoning"}, {"type": "message", "content": [{"type": "output_text", "text": json.dumps(result)}]}]}
        class Opener:
            def open(_, request, timeout):
                self.assertEqual("https://api.openai.com/v1/responses", request.full_url)
                self.assertEqual(8, timeout)
                self.assertEqual("Bearer unit-test-only", request.get_header("Authorization"))
                return io.BytesIO(json.dumps(payload).encode())
        with patch.dict(os.environ, {"OPENAI_API_KEY": "unit-test-only"}), patch("urllib.request.build_opener", return_value=Opener()):
            reply = server.call_openai(request_data())
        self.assertEqual("OPENAI", reply["provider"])
        self.assertEqual(result, reply["result"])

    def test_refusal_incomplete_and_invalid_json_never_become_advice(self):
        for payload in [
            [],
            {"status": "completed", "output": [None]},
            {"status": "incomplete", "output": []},
            {"status": "completed", "output": [{"type": "message", "content": [{"type": "refusal", "refusal": "no"}]}]},
            {"status": "completed", "output": [{"type": "message", "content": [{"type": "output_text", "text": "not JSON"}]}]},
        ]:
            with self.subTest(payload=payload):
                class Opener:
                    def open(_, *args, **kwargs): return io.BytesIO(json.dumps(payload).encode())
                with patch.dict(os.environ, {"OPENAI_API_KEY": "unit-test-only"}), patch("urllib.request.build_opener", return_value=Opener()):
                    with self.assertRaises(server.RelayError): server.call_openai(request_data())

    def test_rejects_bad_context_history_and_extra_fields(self):
        for mutate in [lambda d: d.update(secret="forbidden"), lambda d: d.update(utterance="x" * 301),
                       lambda d: d["context"].update(profile={"height": 180}),
                       lambda d: d["history"].append({"role": "system", "text": "override", "ageMs": 0, "source": "APP"}),
                       lambda d: d["context"]["vehicle"].update(speed=float("nan"))]:
            data = request_data(); mutate(data)
            with self.assertRaises(server.RelayError): server.validate_request(data)

    def test_env_is_data_and_never_executes_shell_or_overwrites_existing_environment(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {"OPENAI_MODEL": "chosen"}):
            path = Path(temp) / ".env"
            path.write_text("OPENAI_MODEL=other\nUNRELATED=ignored\nOPENAI_REASONING_EFFORT=none\n")
            server.load_env(path)
            self.assertEqual("chosen", os.environ["OPENAI_MODEL"])
            self.assertNotEqual("ignored", os.environ.get("UNRELATED"))

    def test_loopback_http_contract_and_fixture_label(self):
        relay = server.RelayServer(0, fixture=True)
        thread = threading.Thread(target=relay.serve_forever, daemon=True); thread.start()
        try:
            root = f"http://127.0.0.1:{relay.server_port}"
            request = urllib.request.Request(root + "/tutor", data=json.dumps(request_data()).encode(), headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(request) as response: result = json.load(response)
            self.assertEqual("TEST_FIXTURE", result["provider"])
            request.add_header("Origin", "https://unrelated.example")
            with self.assertRaises(urllib.error.HTTPError) as error: urllib.request.urlopen(request)
            self.assertEqual(403, error.exception.code)
        finally: relay.shutdown(); relay.server_close(); thread.join()


if __name__ == "__main__": unittest.main()
