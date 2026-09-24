'use strict';

import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
export const REPO = join(HERE, '..', '..');
export const GOLDEN_DIR = join(REPO, 'engine-kotlin', 'env', 'golden', 'replay');
