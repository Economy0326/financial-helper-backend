-- Detect pointer changes even if another activation later restores the same generation ID.
ALTER TABLE active_retrieval_generation
    ADD COLUMN activation_revision BIGINT NOT NULL DEFAULT 0 CHECK (activation_revision >= 0);

CREATE FUNCTION increment_active_generation_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.activation_revision := OLD.activation_revision + 1;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_increment_active_generation_revision
    BEFORE UPDATE ON active_retrieval_generation
    FOR EACH ROW EXECUTE FUNCTION increment_active_generation_revision();
