#!/bin/bash
cd "$(dirname "$0")"
export QT_QPA_PLATFORM="${QT_QPA_PLATFORM:-xcb}"
exec ./dist/PressScribe/PressScribe "$@"
