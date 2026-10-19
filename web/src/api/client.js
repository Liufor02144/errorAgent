import axios from 'axios';
import JSONBig from 'json-bigint';
import { authorization, clearCredentials } from '../auth';

const JSONBigString = JSONBig({ storeAsString: true });

const api = axios.create({
  timeout: 15000,
  headers: { 'Content-Type': 'application/json' },
  withCredentials: true,
  withXSRFToken: true,
  transformResponse: [data => {
    try {
      return JSONBigString.parse(data);
    } catch (e) {
      return data;
    }
  }]
});

api.interceptors.request.use(config => {
  const value = authorization();
  if (value) config.headers.Authorization = value;
  return config;
});

api.interceptors.response.use(response => response, error => {
  if (error.response && error.response.status === 401) {
    clearCredentials();
    window.dispatchEvent(new CustomEvent('erroragent:unauthorized'));
  }
  return Promise.reject(error);
});

export const getApiPath = path => path;
export default api;
