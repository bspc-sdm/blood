// কেয়ারগিভার ও পরিবারের তথ্যের ধরন
export interface CareLink { patientUid: string; caretakerUid: string; patientName: string; caretakerName: string; caretakerEmail: string; status: string; createdAt: number }
export interface FamilyMember { id: string; name: string; relation: string; age: number | null; gender: string; phone: string }
export interface CareData { caretakers: CareLink[]; caringFor: CareLink[]; family: FamilyMember[] }
