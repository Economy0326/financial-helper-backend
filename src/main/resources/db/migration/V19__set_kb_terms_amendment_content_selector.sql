-- The verified KB amendment article stores its article body in .viewCont
-- inside a search form.  Select that article region explicitly so the HTML
-- parser does not discard it with the surrounding form controls.
UPDATE source_registry
SET content_selector = '.viewCont',
    updated_at = CURRENT_TIMESTAMP
WHERE source_key = 'kb-card-terms-amendment-260402';
