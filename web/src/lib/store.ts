// ব্রাউজারে ছোট পছন্দ রাখা (ভাষা, থিম, এলাকা) — প্রাইভেট উইন্ডোতে বা ব্লক থাকলেও পাতা ঠিক চলে
export const store = {
  get(key: string): string | null {
    try {
      return window.localStorage.getItem(key);
    } catch {
      return null;
    }
  },
  set(key: string, value: string | null) {
    try {
      if (value == null) window.localStorage.removeItem(key);
      else window.localStorage.setItem(key, value);
    } catch {
      /* রাখা না গেলেও চলবে */
    }
  },
  getJson<T>(key: string): T | null {
    const s = store.get(key);
    if (!s) return null;
    try {
      return JSON.parse(s) as T;
    } catch {
      return null;
    }
  },
  setJson(key: string, value: unknown) {
    store.set(key, value == null ? null : JSON.stringify(value));
  },
};
