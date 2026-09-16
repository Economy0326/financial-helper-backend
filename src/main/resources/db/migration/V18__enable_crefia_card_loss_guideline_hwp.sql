-- Activate the exact HWP attachment verified from the official association page.
-- The replacement character in the filename is present in the published link.
UPDATE source_registry
SET canonical_url = 'https://m.crefia.or.kr/common/downloadFile.do?fileName=%EC%B9%B4%EB%93%9C_%EB%B6%84%EC%8B%A4%EF%BF%BD%EB%8F%84%EB%82%9C%EC%82%AC%EA%B3%A0_%EB%B3%B4%EC%83%81%EC%97%90_%EA%B4%80%ED%95%9C_%EB%AA%A8%EB%B2%94%EA%B7%9C%EC%A4%80_%28%EA%B0%9C%EC%A0%95_22.10.21.%29_%5B20221213093939007%5D.hwp&fileType=selfRegulation&keyNum=&date=&pFileEnc=',
    enabled = TRUE,
    updated_at = CURRENT_TIMESTAMP
WHERE source_key = 'crefia-card-loss-compensation-guideline-221021';
