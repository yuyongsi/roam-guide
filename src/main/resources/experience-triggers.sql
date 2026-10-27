CREATE TRIGGER IF NOT EXISTS notify_social_reply AFTER INSERT ON social_comments
WHEN NEW.user_id<>(SELECT user_id FROM social_posts WHERE id=NEW.post_id)
BEGIN
 INSERT INTO notifications(user_id,kind,title,body,path)
 SELECT user_id,'reply','帖子收到新回复',substr(NEW.body,1,120),'/community/post/'||id FROM social_posts WHERE id=NEW.post_id AND status='PUBLISHED';
END;
CREATE TRIGGER IF NOT EXISTS notify_feedback_reply AFTER UPDATE OF status,admin_reply ON feedback
WHEN NEW.admin_reply<>'' AND (NEW.admin_reply<>OLD.admin_reply OR NEW.status<>OLD.status)
BEGIN
 INSERT INTO notifications(user_id,kind,title,body,path) VALUES(NEW.user_id,'feedback','意见反馈有了新进展',substr(NEW.admin_reply,1,120),'/feedback');
END;
CREATE TRIGGER IF NOT EXISTS notify_trip_spot_offline AFTER UPDATE OF published ON spots
WHEN OLD.published=1 AND NEW.published=0
BEGIN
 INSERT INTO notifications(user_id,kind,title,body,path)
 SELECT t.user_id,'spot','行程中的景点已下架',NEW.title||'：请检查“'||t.title||'”中的安排。','/trip/'||t.id FROM trips t JOIN trip_items i ON i.trip_id=t.id WHERE i.spot_id=NEW.id;
END;
