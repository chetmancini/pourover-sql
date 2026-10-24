/*
 * Copyright 2026 Chet Mancini
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

-- Fictional sample data for the Pour-Over SQL playground. Column names are deliberately
-- mixed case (quoted) so the key-case toggle has something to convert.
-- Statements are separated by a semicolon at the end of a line.

CREATE SCHEMA SHOP;

CREATE TABLE SHOP.DRINKS (
    "DrinkName" VARCHAR(40) NOT NULL PRIMARY KEY,
    "BasePrice" DECIMAL(6, 2) NOT NULL,
    "IsDecaf" BOOLEAN NOT NULL
);

INSERT INTO SHOP.DRINKS VALUES ('Pour-over', 5.50, FALSE);
INSERT INTO SHOP.DRINKS VALUES ('Espresso', 3.25, FALSE);
INSERT INTO SHOP.DRINKS VALUES ('Cortado', 4.25, FALSE);
INSERT INTO SHOP.DRINKS VALUES ('Cold brew', 4.75, FALSE);
INSERT INTO SHOP.DRINKS VALUES ('Decaf drip', 3.00, TRUE);

CREATE TABLE SHOP.ORDERS (
    "OrderId" INT NOT NULL PRIMARY KEY,
    "CustomerName" VARCHAR(60) NOT NULL,
    "DrinkName" VARCHAR(40) NOT NULL,
    "Quantity" INT NOT NULL,
    "UnitPrice" DECIMAL(6, 2) NOT NULL,
    "PlacedAt" TIMESTAMP NOT NULL,
    "Note" VARCHAR(120)
);

INSERT INTO SHOP.ORDERS VALUES (1, 'Pepper, Penny', 'Pour-over', 1, 5.50, TIMESTAMP '2026-03-02 08:15:00', 'Asked for "extra hot"');
INSERT INTO SHOP.ORDERS VALUES (2, 'Marlowe Quill', 'Espresso', 2, 3.25, TIMESTAMP '2026-03-02 08:22:30', NULL);
INSERT INTO SHOP.ORDERS VALUES (3, 'Juniper Vance', 'Cortado', 1, 4.25, TIMESTAMP '2026-03-02 09:05:10', NULL);
INSERT INTO SHOP.ORDERS VALUES (4, 'Pepper, Penny', 'Cold brew', 3, 4.75, TIMESTAMP '2026-03-03 07:58:45', 'Oat milk, no ice');
INSERT INTO SHOP.ORDERS VALUES (5, 'Tobias Finch', 'Decaf drip', 1, 3.00, TIMESTAMP '2026-03-03 16:40:00', NULL);
INSERT INTO SHOP.ORDERS VALUES (6, 'Marlowe Quill', 'Pour-over', 2, 5.50, TIMESTAMP '2026-03-04 08:10:20', 'Light roast only');
INSERT INTO SHOP.ORDERS VALUES (7, 'Juniper Vance', 'Espresso', 1, 3.25, TIMESTAMP '2026-03-04 12:30:00', NULL);
INSERT INTO SHOP.ORDERS VALUES (8, 'Odette Marsh', 'Cold brew', 1, 4.75, TIMESTAMP '2026-03-05 14:02:59', 'Name on cup: "Dette"');
