import http from 'k6/http';
import { check } from 'k6';

const baseUrl = __ENV.BASE_URL || 'http://backend:8080';
const rate = Number(__ENV.RATE || 25);
const duration = __ENV.DURATION || '1m';
const preAllocatedVUs = Number(__ENV.PREALLOCATED_VUS || Math.max(20, rate));
const maxVUs = Number(__ENV.MAX_VUS || Math.max(100, rate * 4));

export const options = {
    scenarios: {
        authenticatedReads: {
            executor: 'constant-arrival-rate',
            rate,
            timeUnit: '1s',
            duration,
            preAllocatedVUs,
            maxVUs
        }
    },
    thresholds: {
        checks: ['rate>0.99'],
        http_req_failed: ['rate<0.01'],
        http_req_duration: ['p(95)<500', 'p(99)<1000'],
        dropped_iterations: ['count==0']
    },
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max']
};

export function setup() {
    const email = `k6-${Date.now()}@example.com`;
    const jsonHeaders = { 'Content-Type': 'application/json' };
    const registration = http.post(
        `${baseUrl}/api/auth/register`,
        JSON.stringify({
            firstname: 'Load',
            lastname: 'Tester',
            email,
            phoneNumber: '2348012345678',
            password: 'Secure1!'
        }),
        { headers: jsonHeaders, tags: { name: 'SETUP register' } }
    );
    if (!check(registration, { 'registration accepted': response => response.status === 202 })) {
        throw new Error(`Registration failed: ${registration.status} ${registration.body}`);
    }

    const verification = http.post(
        `${baseUrl}/api/auth/verify-email`,
        JSON.stringify({
            email,
            challengeId: registration.json().challengeId,
            code: '000000'
        }),
        { headers: jsonHeaders, tags: { name: 'SETUP verify email' } }
    );
    if (!check(verification, { 'email verified': response => response.status === 200 })) {
        throw new Error(`Email verification failed: ${verification.status} ${verification.body}`);
    }

    const token = verification.json().token;
    const auth = { headers: { Authorization: `Bearer ${token}` } };
    http.get(`${baseUrl}/api/users/me`, auth);
    http.get(`${baseUrl}/api/wallet`, auth);
    http.get(`${baseUrl}/api/wallet/transactions?page=0&size=20`, auth);
    http.get(`${baseUrl}/orders?page=0&size=20`, auth);

    return { token };
}

export default function (data) {
    const headers = { Authorization: `Bearer ${data.token}` };
    const responses = http.batch([
        [
            'GET',
            `${baseUrl}/api/users/me`,
            null,
            { headers, tags: { name: 'GET /api/users/me' } }
        ],
        [
            'GET',
            `${baseUrl}/api/wallet`,
            null,
            { headers, tags: { name: 'GET /api/wallet' } }
        ],
        [
            'GET',
            `${baseUrl}/api/wallet/transactions?page=0&size=20`,
            null,
            { headers, tags: { name: 'GET /api/wallet/transactions' } }
        ],
        [
            'GET',
            `${baseUrl}/orders?page=0&size=20`,
            null,
            { headers, tags: { name: 'GET /orders' } }
        ]
    ]);

    check(responses[0], { 'profile returned 200': response => response.status === 200 });
    check(responses[1], { 'wallet returned 200': response => response.status === 200 });
    check(responses[2], { 'transactions returned 200': response => response.status === 200 });
    check(responses[3], { 'orders returned 200': response => response.status === 200 });
}
