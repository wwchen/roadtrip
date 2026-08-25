-- Bearer token letting an alert email's recipient manage that one watch without
-- signing in. No expiry or revocation: deleting the watch retires the link.
--
-- In the clear, not hashed, because every alert reuses the same link and a hash
-- cannot be un-hashed. A leak grants one watch and no account.
--
-- No index: a link is verified by matching (id, token) together, and the id is
-- already in the request path, so every read is a primary-key lookup.
ALTER TABLE availability_watch
  ADD COLUMN magic_link_token TEXT;
