import unittest
import uuid
from http import HTTPStatus

from retrieval_runtime.runtime import (
    INDEX_BACKEND,
    MODEL_IDENTIFIER,
    MODEL_REVISION,
    TOKEN_VECTOR_DIMENSION,
    _validate_build,
    ContractError,
    RuntimeEngine,
    Handler,
)


class ContractTest(unittest.TestCase):
    def test_build_payload_uses_pinned_model_and_unique_ids(self):
        document_id = str(uuid.uuid4())
        generation_id = str(uuid.uuid4())
        payload = {
            "generationId": generation_id,
            "generationKey": "g1",
            "modelIdentifier": MODEL_IDENTIFIER,
            "modelRevision": MODEL_REVISION,
            "tokenizerIdentifier": MODEL_IDENTIFIER,
            "tokenizerRevision": MODEL_REVISION,
            "chunkConfigVersion": "structure-first-v1",
            "corpusSnapshotSha256": "a" * 64,
            "encodingConfigJson": '{"queryLength":64,"documentMaxTokens":8192,"doQueryExpansion":true,"queryPrefix":"","documentPrefix":""}',
            "indexConfigJson": '{"backend":"PLAID","nbits":4,"seed":42,"useFast":true,"useTriton":false}',
            "documents": [{"id": document_id, "text": "원문"}],
        }
        parsed_id, documents = _validate_build(payload)
        self.assertEqual(generation_id, str(parsed_id))
        self.assertEqual(document_id, documents[0]["id"])
        self.assertEqual(128, TOKEN_VECTOR_DIMENSION)
        self.assertEqual("PLAID", INDEX_BACKEND)

    def test_query_contract_rejects_invalid_generation(self):
        with self.assertRaises(ContractError):
            RuntimeEngine._validate_query_payload({"generationId": "bad", "query": "카드"})

    def test_client_disconnect_during_response_does_not_escape_send(self):
        handler = object.__new__(Handler)
        handler.send_response = lambda status: None
        handler.send_header = lambda name, value: None
        handler.end_headers = lambda: None

        class DisconnectingWriter:
            def write(self, data):
                raise ConnectionAbortedError("client disconnected")

        handler.wfile = DisconnectingWriter()
        handler._send(HTTPStatus.OK, {"ready": True})
        self.assertTrue(handler.close_connection)


if __name__ == "__main__":
    unittest.main()
