// পাতার তালিকা (#/<নাম>) — server/public/admin/app.js-এর PAGES-এর মতো
import type { Component } from 'vue';
import Dashboard from './Dashboard';
import Analytics from './Analytics';
import Emergency from './Emergency';
import { listingPage } from './Listing';
import Verify from './Verify';
import Catalog from './Catalog';
import LabTests from './LabTests';
import { Admins, Corrections, Hospitals, Log, Reports } from './Simple';
import { Ticket, Tickets } from './Tickets';
import { User, Users } from './Users';
import Demo from './Demo';
import { ApptReports } from './ApptReports';

export { Dashboard };
export const PAGES: Record<string, Component> = {
  '': Dashboard,
  analytics: Analytics,
  emergency: Emergency,
  badges: listingPage('badges'),
  pharmacies: listingPage('pharmacies'),
  ambulances: listingPage('ambulances'),
  bloodBanks: listingPage('bloodBanks'),
  labs: listingPage('labs'),
  catalog: Catalog,
  labtests: LabTests,
  hospitals: Hospitals,
  tickets: Tickets,
  ticket: Ticket,
  reports: Reports,
  apptreports: ApptReports,
  corrections: Corrections,
  users: Users,
  user: User,
  log: Log,
  admins: Admins,
  demo: Demo, // ডেমো
  verify: Verify,
};
