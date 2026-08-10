#!/bin/sh
set -eu

readonly SOURCE_DIR=/source
readonly TARGET_DIR=/target
readonly MARKER=.roadtrip-data-sha
readonly INITIALIZING_MARKER=.roadtrip-data-initializing
readonly SHA_FILE=/roadtrip-data-sha

expected_sha="$(cat "${SHA_FILE}")"

if [ -f "${TARGET_DIR}/${MARKER}" ]; then
    actual_sha="$(cat "${TARGET_DIR}/${MARKER}")"
    if [ "${actual_sha}" != "${expected_sha}" ]; then
        echo "error: data volume contains ${actual_sha}, image contains ${expected_sha}" >&2
        exit 1
    fi
    echo "data volume already initialized at ${expected_sha}"
    exit 0
fi

if [ -f "${TARGET_DIR}/${INITIALIZING_MARKER}" ]; then
    initializing_sha="$(cat "${TARGET_DIR}/${INITIALIZING_MARKER}")"
    if [ "${initializing_sha}" != "${expected_sha}" ]; then
        echo "error: interrupted initialization is ${initializing_sha}, expected ${expected_sha}" >&2
        exit 1
    fi
    find "${TARGET_DIR}" -mindepth 1 -delete
elif [ -n "$(find "${TARGET_DIR}" -mindepth 1 -maxdepth 1 -print -quit)" ]; then
    echo "error: refusing to initialize non-empty unversioned data volume" >&2
    exit 1
fi

printf '%s\n' "${expected_sha}" > "${TARGET_DIR}/${INITIALIZING_MARKER}"
cp -a "${SOURCE_DIR}/." "${TARGET_DIR}/"
mv "${TARGET_DIR}/${INITIALIZING_MARKER}" "${TARGET_DIR}/${MARKER}"
echo "initialized data volume at ${expected_sha}"
