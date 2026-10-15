-- Create personal copies of existing global categories.
-- Reuse an existing personal category with the same name and type.
INSERT INTO category (name, type, user_id)
SELECT DISTINCT global_category.name, global_category.type, u.id
FROM category global_category
CROSS JOIN users u
WHERE global_category.user_id IS NULL
  AND NOT EXISTS (
      SELECT 1
      FROM category personal
      WHERE personal.user_id = u.id
        AND personal.name = global_category.name
        AND personal.type = global_category.type
  );

-- Replace global category references with personal category references.
-- If matching personal categories are duplicated, choose the smallest ID.
UPDATE entry e
SET category_id = (
    SELECT MIN(personal.category_id)
    FROM category personal
    WHERE personal.user_id = e.user_id
      AND personal.name = global_category.name
      AND personal.type = global_category.type
)
FROM category global_category
WHERE e.category_id = global_category.category_id
  AND global_category.user_id IS NULL;