package com.lifolo.users

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/** users/{uid} — the profile the app shows and edits (table user_profiles). */
@Entity
@Table(name = "user_profiles")
class UserProfile(
    @Id
    @Column(name = "uid")
    var uid: String = "",
    var name: String? = null,
    var phone: String? = null,
    @Column(name = "blood_group")
    var bloodGroup: String? = null,
    var gender: String? = null,
    @Column(name = "birth_date_ms")
    var birthDateMs: Long? = null,
    @Column(name = "weight_kg")
    var weightKg: Double? = null,
    @Column(name = "last_donation_ms")
    var lastDonationMs: Long? = null,
    var notify: Boolean = true,
    @Column(name = "max_push_per_day")
    var maxPushPerDay: Int? = null,
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "notify_groups")
    var notifyGroups: Array<String>? = null,
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "view_groups")
    var viewGroups: Array<String>? = null,
    @Column(name = "max_km")
    var maxKm: Int? = null,
    @Column(name = "fare_after_km")
    var fareAfterKm: Int? = null,
    var lang: String? = null,
    var photo: String? = null,
    @Column(name = "photo_thumb")
    var photoThumb: String? = null,
    var lat: Double? = null,
    var lng: Double? = null,
    var geohash: String? = null,
    @Column(name = "donor_geo")
    var donorGeo: String? = null,
    @Column(name = "place_name")
    var placeName: String? = null,
    @Column(name = "location_source")
    var locationSource: String? = null,
    @Column(name = "location_updated_at")
    var locationUpdatedAt: Instant? = null,
    @Column(name = "created_at", insertable = false, updatable = false)
    var createdAt: Instant? = null,
    @Column(name = "updated_at")
    var updatedAt: Instant? = null,
)
