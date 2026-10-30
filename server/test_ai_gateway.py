import copy
import io
import json
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from unittest.mock import patch

from ai_gateway import Gateway, OpenAIProvider, StudyError, handler_for, validate_request, validate_result

TOKEN = "synthetic-test-token-not-a-credential-12345"
REQUEST = {"version": 1, "mode": "analyze", "question": "", "language": "English", "sources": [
    {"id": "assignment:1", "title": "Synthetic project", "text": "Deliver a report and a working SQL database. Include three test cases."},
]}
RESULT = {"items": [{"kind": "requirement", "text": "Prepare a report.", "citations": [
    {"sourceId": "assignment:1", "quote": "Deliver a report"},
]}]}


class FakeProvider:
    model = "synthetic-fixture"

    def __init__(self):
        self.requests = []

    def generate(self, request):
        self.requests.append(request)
        return copy.deepcopy(RESULT)


class GatewayTest(unittest.TestCase):
    def test_unauthorized_request_never_reaches_provider(self):
        provider = FakeProvider()
        gateway = Gateway(TOKEN, provider)
        with self.assertRaises(StudyError) as error:
            gateway.handle("Bearer wrong", json.dumps(REQUEST).encode())
        self.assertEqual(401, error.exception.status)
        self.assertEqual([], provider.requests)

    def test_validation_strips_unrelated_fields_and_never_fetches_urls(self):
        request = copy.deepcopy(REQUEST)
        request.update(cookie="synthetic-cookie", accountId="other", url="https://courses.uit.edu.vn/private")
        clean = validate_request(request)
        self.assertEqual(set(REQUEST), set(clean))
        self.assertNotIn("cookie", clean)

    def test_source_and_question_limits_are_enforced(self):
        for mutate in [lambda r: r.update(mode="submit"), lambda r: r.update(sources=[]),
                       lambda r: r.update(sources=r["sources"] * 2),
                       lambda r: r["sources"][0].update(text="a" * 12001),
                       lambda r: r.update(mode="ask", question="")]:
            request = copy.deepcopy(REQUEST)
            mutate(request)
            with self.assertRaises(StudyError):
                validate_request(request)

    def test_unknown_sources_and_fabricated_quotes_are_rejected(self):
        for mutate in [lambda r: r["items"][0]["citations"][0].update(sourceId="other-account"),
                       lambda r: r["items"][0]["citations"][0].update(quote="A fabricated requirement"),
                       lambda r: r["items"][0].update(citations=[])]:
            result = copy.deepcopy(RESULT)
            mutate(result)
            with self.assertRaises(StudyError) as error:
                validate_result(result, REQUEST["sources"], "analyze")
            self.assertEqual("invalid_evidence", error.exception.code)

    def test_unicode_portal_whitespace_uses_the_same_quote_contract_as_android(self):
        for space in ["\u00a0", "\u202f", "\u0085", "\u2003", "\u001c"]:
            sources = copy.deepcopy(REQUEST["sources"])
            sources[0]["text"] = "Deliver" + space + "a report and a working SQL database."
            result = validate_result(copy.deepcopy(RESULT), sources, "analyze")
            self.assertEqual("Deliver a report", result["items"][0]["citations"][0]["quote"])

    def test_suggestions_and_missing_facts_are_distinct_from_extracted_requirements(self):
        result = {"items": [{"kind": "suggestion", "text": "Sketch a schema first.", "citations": []},
                            {"kind": "unknown", "text": "The selected text does not state a due date.", "citations": []}]}
        self.assertEqual(result, validate_result(result, REQUEST["sources"], "analyze"))
        with self.assertRaises(StudyError):
            validate_result(result, REQUEST["sources"], "ask")

    def test_hourly_limit_stops_additional_billable_requests(self):
        provider = FakeProvider()
        gateway = Gateway(TOKEN, provider, hourly_limit=1)
        gateway.handle("Bearer " + TOKEN, json.dumps(REQUEST).encode())
        with self.assertRaises(StudyError) as error:
            gateway.handle("Bearer " + TOKEN, json.dumps(REQUEST).encode())
        self.assertEqual("rate_limit", error.exception.code)
        self.assertEqual(1, len(provider.requests))

    def test_http_contract_has_no_cache_or_cross_origin_redirect(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), handler_for(Gateway(TOKEN, FakeProvider())))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            url = f"http://127.0.0.1:{server.server_address[1]}/v1/study"
            req = urllib.request.Request(url, data=json.dumps(REQUEST).encode(), headers={"Content-Type": "application/json", "Authorization": "Bearer " + TOKEN})
            with urllib.request.urlopen(req) as response:
                self.assertEqual("no-store", response.headers["Cache-Control"])
                data = json.load(response)
            self.assertEqual(1, data["version"])
            self.assertEqual(RESULT["items"], data["items"])
            with self.assertRaises(urllib.error.HTTPError) as error:
                urllib.request.urlopen(urllib.request.Request(url, data=b"{}", headers={"Content-Type": "application/json"}))
            self.assertEqual(401, error.exception.code)
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_openai_adapter_uses_responses_schema_and_disables_response_storage(self):
        payload = {"status": "completed", "output": [{"type": "message", "content": [{"type": "output_text", "text": json.dumps(RESULT)}]}]}
        response = io.BytesIO(json.dumps(payload).encode())
        with patch("ai_gateway.urllib.request.build_opener") as opener:
            opener.return_value.open.return_value = response
            result = OpenAIProvider("synthetic-provider-key", "configured-model").generate(REQUEST)
            call = opener.return_value.open.call_args.args[0]
            data = json.loads(call.data)
        self.assertEqual("https://api.openai.com/v1/responses", call.full_url)
        self.assertFalse(data["store"])
        self.assertEqual("json_schema", data["text"]["format"]["type"])
        self.assertTrue(data["text"]["format"]["strict"])
        self.assertNotIn("tools", data)
        self.assertEqual(RESULT, result)

    def test_refusal_and_incomplete_output_are_not_treated_as_success(self):
        for payload in [{"status": "incomplete", "output": []},
                        {"status": "completed", "output": [{"type": "message", "content": [{"type": "refusal", "refusal": "No"}]}]}]:
            with patch("ai_gateway.urllib.request.build_opener") as opener:
                opener.return_value.open.return_value = io.BytesIO(json.dumps(payload).encode())
                with self.assertRaises(StudyError):
                    OpenAIProvider("synthetic-provider-key", "configured-model").generate(REQUEST)


if __name__ == "__main__":
    unittest.main()
