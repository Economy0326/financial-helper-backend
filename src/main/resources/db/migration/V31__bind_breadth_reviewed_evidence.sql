-- Bind only the hash-pinned official documents frozen by the breadth
-- activation boundary.  Police/KISA pages remain in the generation as
-- supplemental retrieval evidence, but are not required by a Procedure until
-- an explicit applicability window is reviewed for them.
UPDATE procedure_version
SET evidence_references_json = $$[
  {"sourceKey":"fsc-2026-alert","documentVersion":1,"expectedRawSha256":"076b08600a9ad15661e60417dfcd84ff7633f8e76f0e32ef81b7aa55d986cd26","articleReference":null,"pageReference":null,"role":"SAFE_GUIDANCE","historicalApplicabilityRequired":true}
]$$,
    review_notes = 'VOICE_PHISHING MVP v1 binds the reviewed FSC consumer alert; police/KISA supplemental pages remain retrieval-only until applicability review.'
WHERE id = '6aa5b8b5-9c64-4b1e-a70b-1c8a3d8d0c42';

UPDATE procedure_version
SET evidence_references_json = $$[
  {"sourceKey":"fsc-2026-alert","documentVersion":1,"expectedRawSha256":"076b08600a9ad15661e60417dfcd84ff7633f8e76f0e32ef81b7aa55d986cd26","articleReference":null,"pageReference":null,"role":"PROCEDURE","historicalApplicabilityRequired":true}
]$$,
    review_notes = 'UNAUTHORIZED_ACCOUNT_TRANSFER MVP v1 binds the reviewed FSC consumer alert for common reporting/payment-suspension guidance; institution-specific procedure remains out of scope.'
WHERE id = '6aa5b8b5-9c64-4b1e-a70b-1c8a3d8d0c43';

UPDATE procedure_version
SET evidence_references_json = $$[
  {"sourceKey":"fsc-2026-alert","documentVersion":1,"expectedRawSha256":"076b08600a9ad15661e60417dfcd84ff7633f8e76f0e32ef81b7aa55d986cd26","articleReference":null,"pageReference":null,"role":"SAFE_GUIDANCE","historicalApplicabilityRequired":true},
  {"sourceKey":"fsc-personal-info","documentVersion":1,"expectedRawSha256":"4b353ea0d28fd603be638b7553a43251675bf87c40dba16b1c7cbadcf4f615a6","articleReference":null,"pageReference":null,"role":"PROCEDURE","historicalApplicabilityRequired":true}
]$$,
    review_notes = 'PERSONAL_INFO_SMISHING_MALICIOUS_APP MVP v1 binds reviewed FSC smishing and personal-information guidance; KISA/Police pages remain supplemental retrieval evidence.'
WHERE id = '6aa5b8b5-9c64-4b1e-a70b-1c8a3d8d0c44';
