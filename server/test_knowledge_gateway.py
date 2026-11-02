import copy
import json
from pathlib import Path
import re
import unittest
from unittest.mock import patch
import io
import knowledge_contract
from ai_gateway import Gateway, OpenAIProvider, StudyError, validate_request

FIXTURE = json.loads((Path(__file__).parent / 'fixtures/knowledge-map.json').read_text())
REQUEST, RESULT = FIXTURE['request'], FIXTURE['result']

class KnowledgeGatewayTests(unittest.TestCase):
    def test_fixture_contract_and_schema_parity(self):
        self.assertEqual(REQUEST, validate_request(REQUEST))
        self.assertEqual(RESULT, knowledge_contract.result(RESULT, REQUEST['sources']))
        kotlin = (Path(__file__).parent.parent / 'core/data/src/main/kotlin/vn/edu/uit/mobile/data/KnowledgeProtocol.kt').read_text()
        schema = re.search(r'val schema = Json.parseToJsonElement\(\s*"""(.*?)"""\s*\)', kotlin, re.S).group(1)
        self.assertEqual(knowledge_contract.SCHEMA, json.loads(schema))
        instructions = re.search(r'const val INSTRUCTIONS = """(.*?)"""', kotlin, re.S).group(1)
        self.assertEqual(knowledge_contract.SYSTEM, instructions)

    def test_missing_dangling_self_duplicate_and_unquoted_relations_fail(self):
        for transform in [lambda d:d['edges'][0].update(to='outside'), lambda d:d['edges'][0].update(to='bfs'), lambda d:d['edges'].append(copy.deepcopy(d['edges'][0])), lambda d:d['edges'][0].update(citations=[]), lambda d:d['nodes'][0].update(citations=[{'sourceId':'outside','quote':'invented'}])]:
            data = copy.deepcopy(RESULT); transform(data)
            with self.assertRaises(ValueError): knowledge_contract.result(data, REQUEST['sources'])

    def test_disconnected_concepts_and_insufficient_partial_maps_fail(self):
        data = copy.deepcopy(RESULT)
        data['nodes'].append({**data['nodes'][0], 'id':'other','label':'Other'})
        with self.assertRaises(ValueError): knowledge_contract.result(data, REQUEST['sources'])
        insufficient = {'status':'insufficient_evidence','message':'Missing context','title':'','nodes':[],'edges':[]}
        self.assertEqual(insufficient, knowledge_contract.result(insufficient, REQUEST['sources']))
        with self.assertRaises(ValueError): knowledge_contract.result({**insufficient,'nodes':RESULT['nodes']}, REQUEST['sources'])
        for focus in ['', 'x'*401]:
            with self.assertRaises(StudyError): validate_request({**REQUEST,'question':focus})

    def test_gateway_rejects_unsupported_edges_and_omits_private_metadata(self):
        class Provider:
            model = 'synthetic-map-model'
            def generate(self, request):
                self.seen = request
                return RESULT
        provider = Provider()
        gateway = Gateway('synthetic-owner-token-never-real-12345', provider)
        result = gateway.handle('Bearer synthetic-owner-token-never-real-12345', json.dumps({**REQUEST,'universityCookie':'PRIVATE_SENTINEL'}).encode())
        self.assertEqual('ready',result['status'])
        self.assertNotIn('PRIVATE_SENTINEL',json.dumps(provider.seen))
        self.assertEqual(REQUEST,provider.seen)

    def test_provider_has_strict_schema_fixed_origin_and_no_tools(self):
        class Response(io.BytesIO):
            def __enter__(self): return self
            def __exit__(self,*args): self.close()
        seen=[]
        class Opener:
            def open(self,request,timeout):
                seen.append(request)
                return Response(json.dumps({'status':'completed','output':[{'type':'message','content':[{'type':'output_text','text':json.dumps(RESULT)}]}]}).encode())
        with patch('urllib.request.build_opener',return_value=Opener()):
            self.assertEqual(RESULT,OpenAIProvider('synthetic-key','synthetic-model').generate(REQUEST))
        body=json.loads(seen[0].data)
        self.assertEqual('https://api.openai.com/v1/responses',seen[0].full_url)
        self.assertEqual(knowledge_contract.SCHEMA,body['text']['format']['schema'])
        self.assertFalse(body['store']); self.assertNotIn('tools',body)
