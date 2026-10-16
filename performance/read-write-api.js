import http from 'k6/http';
import { check, fail, sleep } from 'k6';

const baseUrl = (__ENV.BASE_URL || 'http://localhost:8080')
  .replace(/\/$/, '');

const usernamePrefix = __ENV.TEST_USERNAME_PREFIX;
const password = __ENV.TEST_PASSWORD;

if (!usernamePrefix || !password) {
  throw new Error('TEST_USERNAME_PREFIX and TEST_PASSWORD are required');
}

if (baseUrl !== 'http://localhost:8080') {
    throw new Error(
        'Load testing is allowed only at http://localhost:8080'
    );
}

export const options = {
  vus: 1,
  duration: '1m',
  noCookiesReset: true,
  thresholds: {
    http_req_failed: ['rate<0.01'],
    checks: ['rate==1'],
    'http_req_duration{operation:read}': ['p(95)<1000'],
    'http_req_duration{operation:write}': ['p(95)<1000'],
  },
};

let initialized = false;
let csrf;
let accountId;
let categoryId;

function requireStatus(response, expectedStatus, label) {
  if (!check(response, {
    [label]: (result) => result.status === expectedStatus,
  })) {
    fail(`${label}: HTTP ${response.status}`);
  }
}

function read(path) {
  const response = http.get(`${baseUrl}${path}`, {
    tags: { operation: 'read', name: path },
    redirects: 0,
    timeout: '10s',
  });

  requireStatus(response, 200, 'read succeeds');
  return response.json();
}

function initialize() {
  const token = read('/api/auth/csrf');

  const login = http.post(
    `${baseUrl}/api/auth/login`,
    {
      username: `${usernamePrefix}${__VU}`,
      password,
    },
    {
      headers: { [token.headerName]: token.token },
      tags: { operation: 'authentication' },
      redirects: 0,
      timeout: '10s',
    }
  );

  requireStatus(login, 200, 'login succeeds');

  // Authentication changes the session's CSRF token.
  csrf = read('/api/auth/csrf');

  const accounts = read('/api/accounts');
  const categories = read('/api/categories?type=EXPENSE');

  if (accounts.length === 0 || categories.length === 0) {
    fail('A personal account and an expense category are required');
  }

  accountId = accounts[0].id;
  categoryId = categories[0].id;
  initialized = true;
}

export default function () {
  if (!initialized) {
    initialize();
  }

  const accounts = read('/api/accounts');
  const account = accounts.find((item) => item.id === accountId);

  if (!account) {
    fail('Test account was not found');
  }

  const balanceBefore = Number(account.amount);

  read('/api/entries?page=0&size=20&type=EXPENSE');
  sleep(1);

  const response = http.post(
    `${baseUrl}/api/entries`,
    JSON.stringify({
      title: `Load test VU ${__VU}, iteration ${__ITER}`,
      type: 'EXPENSE',
      amount: 1.25,
      dateTime: new Date().toISOString().slice(0, 19),
      description: 'Synthetic local load test expense',
      accountId,
      categoryId,
    }),
    {
      headers: {
        'Content-Type': 'application/json',
        [csrf.headerName]: csrf.token,
      },
      tags: { operation: 'write', name: 'create expense' },
      redirects: 0,
      timeout: '10s',
    }
  );

  requireStatus(response, 201, 'expense is created');

  const updatedAccount = read('/api/accounts')
    .find((item) => item.id === accountId);

  const balanceIsCorrect = check(updatedAccount, {
    'balance decreases by 1.25': (item) =>
      item !== undefined &&
      Math.abs(Number(item.amount) - (balanceBefore - 1.25)) < 0.001,
  });

  if (!balanceIsCorrect) {
    fail('Unexpected account balance');
  }

  read('/api/entries/summary');
  sleep(3);
}