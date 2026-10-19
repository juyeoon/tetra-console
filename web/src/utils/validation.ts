export const isEmail = (v: string) => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v.trim());

export const isDomain = (v: string) => /^(?!-)[a-z0-9-]+(\.[a-z0-9-]+)+$/i.test(v.trim());
