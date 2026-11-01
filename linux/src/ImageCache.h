#pragma once

#include <QQmlNetworkAccessManagerFactory>

// Keeps the images QML loads (covers, artist photos) on disk, in the user's cache folder.
class ImageCache : public QQmlNetworkAccessManagerFactory
{
public:
    QNetworkAccessManager *create(QObject *parent) override;
};
