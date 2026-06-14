using System.ComponentModel.DataAnnotations.Schema;

namespace webapi.Models
{
    [Table("devices")]
    public class Device
    {
        [Column("id")]
        public int Id { get; set; }

        [Column("device_name")]
        public string DeviceName { get; set; } = string.Empty;

        [Column("device_model")]
        public string DeviceModel { get; set; } = string.Empty;

        [Column("device_location")]
        public string? DeviceLocation { get; set; }

        [Column("created_at")]
        public DateTime CreatedAt { get; set; }
    }
}
