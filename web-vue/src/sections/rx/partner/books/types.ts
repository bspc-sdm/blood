import type { ShopItem } from './model';

export type Filter = 'all' | 'low' | 'exp' | 'out';
export type Draft = Partial<ShopItem> & { name: string };
