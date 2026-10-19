const AUTH_KEY = 'erroragent.basicAuth';
const USER_KEY = 'erroragent.username';

function utf8Base64(value) {
  const bytes = new TextEncoder().encode(value);
  let binary = '';
  bytes.forEach(byte => { binary += String.fromCharCode(byte); });
  return btoa(binary);
}

export function authorization() {
  return sessionStorage.getItem(AUTH_KEY);
}

export function username() {
  return sessionStorage.getItem(USER_KEY) || '';
}

export function saveCredentials(user, password) {
  sessionStorage.setItem(AUTH_KEY, `Basic ${utf8Base64(`${user}:${password}`)}`);
  sessionStorage.setItem(USER_KEY, user);
}

export function clearCredentials() {
  sessionStorage.removeItem(AUTH_KEY);
  sessionStorage.removeItem(USER_KEY);
}
