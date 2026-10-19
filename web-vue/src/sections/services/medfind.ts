export interface Drug { i: number; name: string; generic: string; form: string; company: string }
export interface FindShop { id: string; name: string; phone: string; address: string; openNow: boolean; open24: boolean; km: number | null }
export interface MyFind { id: string; drug: Drug; area: string; open: boolean; notified: number; answered: number; have: FindShop[]; createdAt: number }

export const drugTitle = (d: Drug) => d.name + (d.form ? ' · ' + d.form : '');
export const drugDetail = (d: Drug) => [d.generic, d.form, d.company].filter(Boolean).join(' · ');
