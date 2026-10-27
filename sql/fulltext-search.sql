CREATE VIRTUAL TABLE IF NOT EXISTS search_fts USING fts5(
 title,intro,body,location,type UNINDEXED,item_id UNINDEXED,
 tokenize='unicode61 remove_diacritics 0'
);
CREATE TABLE IF NOT EXISTS search_dirty (
 type TEXT NOT NULL CHECK(type IN ('spot','guide')),
 item_id INTEGER NOT NULL,
 PRIMARY KEY(type,item_id)
);
CREATE TRIGGER IF NOT EXISTS search_spot_insert AFTER INSERT ON spots
BEGIN
 INSERT OR IGNORE INTO search_dirty(type,item_id) VALUES('spot',NEW.id);
END;
CREATE TRIGGER IF NOT EXISTS search_spot_update AFTER UPDATE OF title,intro,description,highlights,transportation,tips,city_id,published ON spots
BEGIN
 INSERT OR IGNORE INTO search_dirty(type,item_id) VALUES('spot',NEW.id);
END;
CREATE TRIGGER IF NOT EXISTS search_spot_delete AFTER DELETE ON spots
BEGIN
 INSERT OR IGNORE INTO search_dirty(type,item_id) VALUES('spot',OLD.id);
END;
CREATE TRIGGER IF NOT EXISTS search_guide_insert AFTER INSERT ON guides
BEGIN
 INSERT OR IGNORE INTO search_dirty(type,item_id) VALUES('guide',NEW.id);
END;
CREATE TRIGGER IF NOT EXISTS search_guide_update AFTER UPDATE OF title,intro,subtitle,sections,city_id,published ON guides
BEGIN
 INSERT OR IGNORE INTO search_dirty(type,item_id) VALUES('guide',NEW.id);
END;
CREATE TRIGGER IF NOT EXISTS search_guide_delete AFTER DELETE ON guides
BEGIN
 INSERT OR IGNORE INTO search_dirty(type,item_id) VALUES('guide',OLD.id);
END;
CREATE TRIGGER IF NOT EXISTS search_city_update AFTER UPDATE OF name,province ON cities
BEGIN
 INSERT OR IGNORE INTO search_dirty(type,item_id) SELECT 'spot',id FROM spots WHERE city_id=NEW.id;
 INSERT OR IGNORE INTO search_dirty(type,item_id) SELECT 'guide',id FROM guides WHERE city_id=NEW.id;
END;
