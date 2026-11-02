import copy
import json
import pathlib
import unittest
from unittest import mock

import ai_gateway as gateway
import tutor_contract as tutor


ROOT = pathlib.Path(__file__).resolve().parent
FIXTURE = json.loads((ROOT / 'fixtures/tutor-python.json').read_text())


class Provider:
    model = 'synthetic-code-tutor'

    def __init__(self):
        self.calls = []

    def generate(self, request):
        self.calls.append(request)
        return copy.deepcopy(FIXTURE['result'])


class TutorGatewayTest(unittest.TestCase):
    def setUp(self):
        self.request = copy.deepcopy(FIXTURE['request'])
        self.result = copy.deepcopy(FIXTURE['result'])

    def test_android_and_gateway_schemas_are_equal(self):
        kotlin = (ROOT.parent / 'core/data/src/main/kotlin/vn/edu/uit/mobile/data/TutorProtocol.kt').read_text()
        encoded = kotlin.split('val schema = Json.parseToJsonElement("""', 1)[1].split('"""', 1)[0]
        self.assertEqual(tutor.SCHEMA, json.loads(encoded))

    def test_standalone_request_preserves_numbered_indentation_and_strips_unrelated_fields(self):
        self.request['account'] = 'PRIVATE_OWNER_SENTINEL'
        self.request['cookie'] = 'PRIVATE_COOKIE_SENTINEL'
        self.request['tutor']['unrelatedRepository'] = 'PRIVATE_REPOSITORY_SENTINEL'
        provider = Provider()
        server = gateway.Gateway('synthetic-gateway-token-not-a-real-secret', provider)
        result = server.handle('Bearer synthetic-gateway-token-not-a-real-secret', json.dumps(self.request).encode())
        self.assertEqual(FIXTURE['request'], provider.calls[0])
        self.assertEqual('    return values[0]', provider.calls[0]['tutor']['lines'][1]['text'])
        self.assertEqual(3, len(result['hints']))

    def test_line_numbering_types_and_bounds_are_checked_before_provider_access(self):
        for value in [True, '10', 0, 1000001]:
            bad = copy.deepcopy(self.request)
            bad['tutor']['firstLine'] = value
            with self.assertRaises(gateway.StudyError):
                gateway.validate_request(bad)
        bad = copy.deepcopy(self.request)
        bad['tutor']['lines'][1]['number'] = 30
        with self.assertRaises(gateway.StudyError):
            gateway.validate_request(bad)

    def test_real_code_quote_must_occur_in_its_declared_line_span(self):
        bad = self.result['observations'][0]['codeReferences'][0]
        bad['startLine'] = bad['endLine'] = 10
        with self.assertRaises(ValueError):
            tutor.result(self.result, self.request)

    def test_code_spacing_cannot_be_normalized_into_fabricated_evidence(self):
        self.result['observations'][0]['codeReferences'][0]['quote'] = 'return  values[0]'
        with self.assertRaises(ValueError):
            tutor.result(self.result, self.request)

    def test_course_evidence_requires_selected_source_and_literal_markup(self):
        point = self.result['observations'][0]
        point.update(kind='course', citations=[{'sourceId': 'lesson', 'quote': 'A **stack** is last-in first-out.'}])
        with self.assertRaises(ValueError):
            tutor.result(self.result, self.request)
        self.request['sources'] = [{'id': 'lesson', 'title': 'Synthetic lesson', 'text': 'A **stack** is last-in first-out.'}]
        tutor.result(self.result, self.request)
        point['citations'][0]['quote'] = 'A stack is last-in first-out.'
        with self.assertRaises(ValueError):
            tutor.result(self.result, self.request)

    def test_hint_order_counts_test_references_and_preview_edit_are_validated(self):
        self.result['hints'] = self.result['hints'][:2]
        with self.assertRaises(ValueError):
            tutor.result(self.result, self.request)
        self.result = copy.deepcopy(FIXTURE['result'])
        self.result['tests'][0]['codeReferences'] = []
        with self.assertRaises(ValueError):
            tutor.result(self.result, self.request)
        self.result = copy.deepcopy(FIXTURE['result'])
        self.result['editReason'] = ''
        with self.assertRaises(ValueError):
            tutor.result(self.result, self.request)

    def test_insufficient_context_cannot_silently_publish_a_partial_result(self):
        unknown = dict(status='insufficient_context', summary='Need an implementation.', observations=[], hints=[], tests=[], proposedCode='', editReason='')
        self.assertEqual([], tutor.result(unknown, self.request)['tests'])
        unknown['tests'] = self.result['tests']
        with self.assertRaises(ValueError):
            tutor.result(unknown, self.request)

    def test_provider_uses_fixed_origin_strict_schema_and_no_execution_tools(self):
        raw = {'status': 'completed', 'output': [{'type': 'message', 'content': [{'type': 'output_text', 'text': json.dumps(self.result)}]}]}
        response = mock.MagicMock()
        response.__enter__.return_value.read.return_value = json.dumps(raw).encode()
        opener = mock.Mock()
        opener.open.return_value = response
        with mock.patch('urllib.request.build_opener', return_value=opener):
            gateway.OpenAIProvider('synthetic-key', 'gpt-6-luna').generate(self.request)
        req = opener.open.call_args.args[0]
        payload = json.loads(req.data)
        self.assertEqual('https://api.openai.com/v1/responses', req.full_url)
        self.assertFalse(payload['store'])
        self.assertNotIn('tools', payload)
        self.assertEqual(tutor.SCHEMA, payload['text']['format']['schema'])
        self.assertEqual('code_tutor_result', payload['text']['format']['name'])


if __name__ == '__main__':
    unittest.main()
