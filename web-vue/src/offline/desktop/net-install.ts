// সবার আগে (ওয়েবের main.ts-এর আগে) — /api/… ডাক Rust-এর HTTP দিয়ে
import { installNet } from './net';

installNet();
