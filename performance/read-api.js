import http from 'k6/http';
import { check, fail, sleep } from 'k6';

const baseUrl = (__ENV.BASE_URL || 'http://localhost:8080')
  .replace(/\/$/, '');

if (baseUrl !== 'http://localhost:8080') {
    throw new Error(
        'Load testing is allowed only at http://localhost:8080'
    );
}

const username = __ENV.TEST_USERNAME;
const usernamePrefix = __ENV.TEST_USERNAME_PREFIX;
const password = __ENV.TEST_PASSWORD;

if ((!username && !usernamePrefix) || !password) {
  throw new Error(
    'TEST_USERNAME or TEST_USERNAME_PREFIX, and TEST_PASSWORD are required'
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
  },
};

let authenticated = false;

function login() {
  const csrfResponse = http.get(`${baseUrl}/api/auth/csrf`, {
    tags: { operation: 'authentication' },
    timeout: '10s',
  });

  if (!check(csrfResponse, {
    'CSRF request succeeds': (response) => response.status === 200,
  })) {
    fail('Could not obtain CSRF token');
  }

  const csrf = csrfResponse.json();

  const loginUsername = usernamePrefix
    ? `${usernamePrefix}${__VU}`
    : username;

  const loginResponse = http.post(
    `${baseUrl}/api/auth/login`,
    { username: loginUsername, password },
    {
      headers: {
        [csrf.headerName]: csrf.token,
      },
      tags: { operation: 'authentication' },
      redirects: 0,
      timeout: '10s',
    }
  );

  if (!check(loginResponse, {
    'login succeeds': (response) => response.status === 200,
  })) {
    fail('Login failed');
  }

  authenticated = true;
}

export default function () {
  if (!authenticated) {
    login();
  }

  const paths = [
    '/api/accounts',
    '/api/categories',
    '/api/entries?page=0&size=20&type=EXPENSE',
    '/api/entries/summary',
  ];

  for (const path of paths) {
    const response = http.get(`${baseUrl}${path}`, {
      tags: { operation: 'read', name: path },
      redirects: 0,
      timeout: '10s',
    });

    check(response, {
      'API returns 200': (result) => result.status === 200,
    });

    if (response.status === 401) {
      authenticated = false;
      fail('Session expired or authentication was lost');
    }

    sleep(1);
  }

  sleep(2);
}